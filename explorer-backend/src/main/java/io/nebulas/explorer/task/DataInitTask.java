package io.nebulas.explorer.task;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.nebulas.explorer.domain.BlockSyncRecord;
import io.nebulas.explorer.domain.NebAddress;
import io.nebulas.explorer.domain.NebBlock;
import io.nebulas.explorer.domain.NebTransaction;
import io.nebulas.explorer.enums.NebAddressTypeEnum;
import io.nebulas.explorer.enums.NebTransactionTypeEnum;
import io.nebulas.explorer.model.Block;
import io.nebulas.explorer.model.NebState;
import io.nebulas.explorer.model.Transaction;
import io.nebulas.explorer.model.Zone;
import io.nebulas.explorer.service.blockchain.*;
import io.nebulas.explorer.service.thirdpart.nebulas.NebulasApiService;
import io.nebulas.explorer.service.thirdpart.nebulas.bean.*;
import io.nebulas.explorer.util.BlockHelper;
import lombok.AllArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.alibaba.fastjson.JSON.toJSONString;

/**
 * Desc:
 * User: nathan
 * Date: 2018-02-26
 */
@AllArgsConstructor
@Service
public class DataInitTask {
    private static final Logger LOGGER = LoggerFactory.getLogger("data_init");
    private final NebBlockService nebBlockService;
    private final NebTransactionService nebTransactionService;
    private final NebAddressService nebAddressService;
    private final NebDynastyService nebDynastyService;
    private final BlockSyncRecordService blockSyncRecordService;
    private final NebulasApiService nebulasApiService;

    private static final Base64.Decoder DECODER = Base64.getDecoder();

    public void init(boolean isSync) {
        if (!isSync) {
            return;
        }
        NebState nebState = nebulasApiService.getNebState().toBlocking().first();
        if (nebState == null) {
            LOGGER.error("neb state not found");
            return;
        }
        LOGGER.info("neb state: {}", toJSONString(nebState));

        Block block = nebulasApiService.getBlockByHash(new GetBlockByHashRequest(nebState.getTail(), false)).toBlocking().first();
        if (block == null) {
            LOGGER.error("block by hash {} not found", nebState.getTail());
            return;
        }
        LOGGER.info("top block: {}", toJSONString(block));

        final Long goalHeight = block.getHeight();
        final Long lastConfirmHeight = blockSyncRecordService.getMaxConfirmedBlockHeight();
        List<Zone> zoneList = divideZones(lastConfirmHeight, goalHeight);
        populateZones(zoneList);
    }

    private void populateZones(List<Zone> zones) {
        if (zones.size() > 0) {
            LOGGER.info("zones {}", zones);
            ExecutorService executor = Executors.newFixedThreadPool(1);
            for (Zone zone : zones) {
                executor.execute(() -> {
                    populate(zone.getFrom(), zone.getTo());
                });
            }
        }
    }

    private void populate(long from, long to) {
        long threadId = Thread.currentThread().getId();
        LOGGER.info("Thread {} start populating", threadId);

        long start = System.currentTimeMillis();
        Block latestIrreversibleBlk = nebulasApiService.getLatestIrreversibleBlock().toBlocking().first();
        LOGGER.info("get latestIrreversibleBlk height={}", latestIrreversibleBlk.getHeight());

        for (long h = from; h <= to; ) {
            BlockSyncRecord record = new BlockSyncRecord();
            record.setBlockHeight(h);
            record.setTxCnt(0L);
            record.setConfirm(0L);
            blockSyncRecordService.add(record);

            NebBlock nebBlock = nebBlockService.getNebBlockByHeight(h);
            if (nebBlock != null) {
                h++;
                LOGGER.info("block exist, height={}", nebBlock.getHeight());
                continue;
            }

            try {
                Block blk = nebulasApiService.getBlockByHeight(new GetBlockByHeightRequest(h, true)).toBlocking().first();
                if (blk == null) {
                    LOGGER.error("block with height {} not found", h);
                    h++;
                    continue;
                }
                batchSaveAddress(Arrays.asList(blk.getMiner(), blk.getCoinbase()), NebAddressTypeEnum.NORMAL);

                NebBlock nblk = nebBlockService.getNebBlockByHash(blk.getHash());
                if (nblk == null) {
                    nebBlockService.addNebBlock(BlockHelper.buildNebBlock(blk, latestIrreversibleBlk.getHeight()));
                    LOGGER.info("save block, height={}", blk.getHeight());
                } else {
                    LOGGER.warn("duplicate block hash {}", blk.getHash());
                }

                GetDynastyResponse dynastyResponse = nebulasApiService.getDynasty(new GetDynastyRequest(blk.getHeight())).toBlocking().first();
                nebDynastyService.batchAddNebDynasty(blk.getHeight(), dynastyResponse.getDelegatees());

                batchSaveAddress(dynastyResponse.getDelegatees(), NebAddressTypeEnum.NORMAL);

                List<Transaction> txs = blk.getTransactions();
                LOGGER.info("get txs {}", txs.size());
                for (Transaction tx : txs) {
                    addAddr(tx.getFrom(), NebAddressTypeEnum.NORMAL);

                    if (NebTransactionTypeEnum.BINARY.getDesc().equals(tx.getType())) {
                        addAddr(tx.getTo(), NebAddressTypeEnum.NORMAL);
                    } else if (NebTransactionTypeEnum.CALL.getDesc().equals(tx.getType())) {
                        addAddr(tx.getTo(), NebAddressTypeEnum.CONTRACT);
                        String realReceiver = extractReceiverAddress(tx.getData());
                        addAddr(realReceiver, NebAddressTypeEnum.NORMAL);
                    } else if (NebTransactionTypeEnum.DEPLOY.getDesc().equals(tx.getType())) {
                        addAddr(tx.getContractAddress(), NebAddressTypeEnum.NORMAL);
                    }

                    NebTransaction nebTx = BlockHelper.buildNebTransaction(tx, blk);

                    if (StringUtils.isEmpty(tx.getGasUsed())) {
                        String gasUsed = null;
                        try {
                            GetGasUsedResponse gasUsedResponse = nebulasApiService.getGasUsed(new GetGasUsedRequest(tx.getHash())).toBlocking().first();
                            gasUsed = gasUsedResponse.getGas();
                        } catch (Exception e) {
                            LOGGER.error("get gas used by tx hash error", e);
                        }
                        if (gasUsed != null) {
                            nebTx.setGasUsed(gasUsed);
                            LOGGER.info("tx hash {} gas used: {} ", tx.getHash(), gasUsed);
                        } else {
                            nebTx.setGasUsed("");
                            LOGGER.warn("gas used not found for tx hash {}", tx.getHash());
                        }
                    }
                    nebTransactionService.addNebTransaction(nebTx);
                    LOGGER.info("save tx={}", tx.getHash());
                }
            } catch (Exception e) {
                LOGGER.error(e.getMessage(), e);
            }
            h++;
        }
        LOGGER.info("Thread {}: {} millis elapsed for populating", threadId, System.currentTimeMillis() - start);
    }

    private String extractReceiverAddress(String data) {
        try {
            String dataStr = new String(DECODER.decode(data), "UTF-8");
            JSONObject jsonObject = JSONObject.parseObject(dataStr);
            String func = jsonObject.getString("Function");

            if ("transfer".equals(func)) {
                JSONArray array = jsonObject.getJSONArray("Args");
                return array.getString(0);
            }
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
        return "";
    }

    private void addAddr(String hash, NebAddressTypeEnum type) {
        NebAddress addr = nebAddressService.getNebAddressByHash(hash);
        if (addr == null) {
            nebAddressService.addNebAddress(hash, type.getValue());
        }
    }

    private void batchSaveAddress(List<String> addressHash, NebAddressTypeEnum type) {
        if (CollectionUtils.isEmpty(addressHash)) {
            return;
        }
        for (String hash : addressHash) {
            addAddr(hash, type);
        }
    }

    private List<Zone> divideZones(long from, long to) {
        if (from >= to) {
            return new ArrayList<>(0);
        }
        final long total = to - from + 1;

        final Long zoneThreshold = 20000L;

        if (total < zoneThreshold) {
            return Arrays.asList(new Zone(from, to));
        }

        long zoneSize = total / zoneThreshold + 1;
        List<Zone> zoneList = new ArrayList<>((int) zoneSize);
        for (int i = 1; i <= zoneSize; i++) {
            long end = from + zoneThreshold;
            if (end > to) {
                end = to;
            }
            zoneList.add(new Zone(from, end));
            from = end;
        }
        return zoneList;
    }
}
