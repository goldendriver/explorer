package io.nebulas.explorer.controller;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.nebulas.explorer.domain.*;
import io.nebulas.explorer.enums.NebTransactionStatusEnum;
import io.nebulas.explorer.model.JsonResult;
import io.nebulas.explorer.model.PageIterator;
import io.nebulas.explorer.model.vo.AddressVo;
import io.nebulas.explorer.model.vo.BlockVo;
import io.nebulas.explorer.model.vo.TransactionVo;
import io.nebulas.explorer.service.NebAddressService;
import io.nebulas.explorer.service.NebBlockService;
import io.nebulas.explorer.service.NebMarketCapitalizationService;
import io.nebulas.explorer.service.NebTransactionService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.LocalDate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Explorer http rpc gateway
 *
 * @author nathan wang
 * @version 1.0
 * @since 2018-01-29
 */
@Slf4j
@AllArgsConstructor
@RequestMapping("/api")
@RestController
public class RpcController {
    private static final Integer PAGE_SIZE = 25;
    private static final int MAX_PAGE = 400;

    private final NebAddressService nebAddressService;
    private final NebBlockService nebBlockService;
    private final NebTransactionService nebTransactionService;
    private final NebMarketCapitalizationService nebMarketCapitalizationService;

    @RequestMapping(value = "/market_cap", method = RequestMethod.GET)
    public JsonResult marketCap() {
        return JsonResult.success(nebMarketCapitalizationService.getLatest());
    }

    @RequestMapping(value = "/block", method = RequestMethod.GET, params = "type=latest")
    public JsonResult latestBlock() {
        List<NebBlock> blkList = nebBlockService.findNebBlockOrderByHeight(1, 10);
        return JsonResult.success(convertBlock2BlockVo(blkList));
    }

    @RequestMapping(value = "/block")
    public JsonResult blocks(@RequestParam(value = "m", required = false) String miner,
                             @RequestParam(value = "p", required = false, defaultValue = "1") int page) {
        PageIterator<NebBlock> blockPageIterator;

        if (StringUtils.isEmpty(miner)) {
            blockPageIterator = nebBlockService.findNebBlockPageIterator(page, PAGE_SIZE);
        } else {
            blockPageIterator = nebBlockService.findNebBlockPageIteratorByMiner(miner, page, PAGE_SIZE);
        }

        PageIterator<BlockVo> blockVoPageIterator = PageIterator.create(blockPageIterator.getPage(), blockPageIterator.getPageSize(), blockPageIterator.getTotalCount());
        blockVoPageIterator.setData(convertBlock2BlockVo(blockPageIterator.getData()));

        return JsonResult.success(blockVoPageIterator);
    }

    @RequestMapping(value = "/block/{blkKey}", method = RequestMethod.GET)
    public JsonResult block(@PathVariable("blkKey") String blkKey) {
        NebBlock block;
        if (StringUtils.isNumeric(blkKey)) {
            block = nebBlockService.getNebBlockByHeight(Long.valueOf(blkKey));
        } else {
            block = nebBlockService.getNebBlockByHash(blkKey);
        }

        if (null == block) {
            return JsonResult.failed("block does not exist");
        }

        JsonResult result = JsonResult.success(block);
        result.put("blkMaxHeight", nebBlockService.getMaxHeight());

        List<NebTransaction> txnList = nebTransactionService.findTxnByBlockHeight(block.getHeight());
        BigDecimal blkGasUsed = BigDecimal.ZERO;
        BigDecimal blkGasLimit = BigDecimal.ZERO;
        for (NebTransaction txn : txnList) {
            blkGasUsed = blkGasUsed.add(StringUtils.isEmpty(txn.getGasUsed()) ? BigDecimal.ZERO : new BigDecimal(txn.getGasUsed()));
            blkGasLimit = blkGasLimit.add(StringUtils.isEmpty(txn.getGasLimit()) ? BigDecimal.ZERO : new BigDecimal(txn.getGasLimit()));
        }
        result.put("txCnt", txnList.size());
        result.put("blkGasUsed", blkGasUsed.toPlainString());
        result.put("blkGasLimit", blkGasLimit.toPlainString());
        result.put("blkGasUsedRate", BigDecimal.ZERO.compareTo(blkGasLimit) < 0 ? blkGasUsed.divide(blkGasLimit, 2, BigDecimal.ROUND_DOWN).multiply(new BigDecimal(100)).toPlainString() : "0");

        NebAddress nebAddress = nebAddressService.getNebAddressByHash(block.getMiner());
        result.put("miner", null != nebAddress ? nebAddress : new NebAddress(block.getMiner()));
        return result;
    }

    @RequestMapping(value = "/tx", method = RequestMethod.GET, params = "type=latest")
    public JsonResult latestTransaction() {
        List<NebTransaction> txnList = nebTransactionService.findTxnOrderById(1, 10);
        return JsonResult.success(convertTxn2TxnVo(txnList));
    }

    @RequestMapping(value = "/tx", method = RequestMethod.GET)
    public JsonResult transactions(@RequestParam(value = "block", required = false) Long block,
                                   @RequestParam(value = "a", required = false) String address,
                                   @RequestParam(value = "p", required = false, defaultValue = "1") int page,
                                   @RequestParam(value = "isPending", required = false, defaultValue = "false") Boolean isPending) {
        String type;
        if (null != block) {
            type = "block";
        } else if (StringUtils.isNoneEmpty(address)) {
            type = "address";
        } else {
            type = "total";
        }

        JsonResult result = JsonResult.success();
        long txnCnt;
        if (!isPending) {
            if (page > 20) {
                page = 20;
            }
            txnCnt = nebTransactionService.countTxnCnt(block, address);
            List<NebTransaction> txnList = nebTransactionService.findTxnByCondition(block, address, page, PAGE_SIZE);
            result.put("txnList", convertTxn2TxnVo(txnList));
        } else {
            txnCnt = nebTransactionService.countPendingTxnCnt(address);
            List<NebPendingTransaction> pendingTxnList = nebTransactionService.findPendingTxnByCondition(address, page, PAGE_SIZE);
            result.put("txnList", convertPendingTxn2TxnVo(pendingTxnList));
        }

        result.put("type", type);
        result.put("txnCnt", txnCnt);
        result.put("currentPage", page);
        result.put("totalPage", txnCnt / PAGE_SIZE + 1);
        return result;
    }

    @RequestMapping("/tx/{txHash}")
    public JsonResult tx(@PathVariable("txHash") String txHash) {
        boolean isPending = false;
        NebTransaction txn = nebTransactionService.getNebTransactionByHash(txHash);
        if (null == txn) {
            NebPendingTransaction pendingTxn = nebTransactionService.getNebPendingTransactionByHash(txHash);
            if (null != pendingTxn) {
                txn = new NebTransaction();
                try {
                    PropertyUtils.copyProperties(txn, pendingTxn);
                    txn.setStatus(NebTransactionStatusEnum.PENDING.getValue());
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
                isPending = true;
            }
        }

        if (null == txn) {
            return JsonResult.failed("transaction does not exist");
        }

        List<String> addressHashList = Arrays.asList(txn.getFrom(), txn.getTo());
        Map<String, NebAddress> nebAddressMap = nebAddressService.findAddressMapByAddressHash(addressHashList);

        TransactionVo vo = new TransactionVo();
        vo.build(txn);
        if (!isPending) {
            vo.setBlock(new BlockVo(txn.getBlockHash(), txn.getBlockHeight()));
        }

        NebAddress fromAddress = nebAddressMap.get(txn.getFrom());
        vo.setFrom(fromAddress != null ? (new AddressVo().build(fromAddress)) : new AddressVo(txn.getFrom()));

        NebAddress toAddress = nebAddressMap.get(txn.getTo());
        vo.setTo(toAddress != null ? (new AddressVo().build(toAddress)) : new AddressVo(txn.getTo()));

        JsonResult result = JsonResult.success();
        result.add(vo);
        result.put("isPending", isPending);
        return result;
    }

    @RequestMapping("/tx/cnt_static")
    public JsonResult txStatic() {
        return JsonResult.success(nebTransactionService.countTxCntGroupMapByTimestamp(LocalDate.now().plusDays(-15).toDate(), LocalDate.now().toDate()));
    }

    @RequestMapping("/account")
    public JsonResult accounts(@RequestParam(value = "p", required = false, defaultValue = "1") int page) {
        if (page < 1) {
            page = 1;
        }
        if (page > MAX_PAGE) {
            return JsonResult.failed();
        }

        BigDecimal totalBalance = BigDecimal.ONE;//todo
        List<NebAddress> addressList = nebAddressService.findAddressOrderByBalance(page, PAGE_SIZE);
        Map<String, BigDecimal> percentageMap = addressList.stream()
                .collect(Collectors.toMap(NebAddress::getHash, a -> a.getCurrentBalance().divide(totalBalance, 8, BigDecimal.ROUND_DOWN)));

        List<String> addressHashList = addressList.stream().map(NebAddress::getHash).collect(Collectors.toList());
        Map<String, Long> txCntMap = nebTransactionService.countTxnCntByFromTo(addressHashList);

        List<AddressVo> voList = Lists.newLinkedList();
        int i = 1 + (page - 1) * PAGE_SIZE;
        for (NebAddress address : addressList) {
            AddressVo vo = new AddressVo().build(address);
            vo.setRank(i);
            vo.setPercentage(percentageMap.get(address.getHash()));
            vo.setTxCnt(txCntMap.get(address.getHash()));
            voList.add(vo);
            i++;
        }

        JsonResult result = JsonResult.success();
        long totalAccountsCnt = nebAddressService.countTotalAddressCnt();
        long totalPage = totalAccountsCnt / PAGE_SIZE + 1;
        result.put("totalAccountsCnt", totalAccountsCnt);
        result.put("totalBalance", totalBalance);
        result.put("page", page);
        result.put("addressList", voList);
        result.put("totalPage", totalPage > MAX_PAGE ? MAX_PAGE : totalPage);
        return result;
    }

    @RequestMapping("/address/{hash}")
    public JsonResult address(@PathVariable("hash") String hash, @RequestParam(value = "part", required = false) String part) {
        NebAddress address = nebAddressService.getNebAddressByHash(hash);
        if (null == address) {
            return JsonResult.failed();
        }
        long pendingTxCnt = nebTransactionService.countPendingTxnCnt(address.getHash());

        JsonResult result = JsonResult.success();
        result.put("address", address);
        result.put("pendingTxCnt", pendingTxCnt);
        result.put("txCnt", nebTransactionService.countTxnCntByFromTo(address.getHash()));
        result.put("minedBlkCnt", nebBlockService.countBlockCntByMiner(address.getHash()));

        if ("mine".equals(part)) {
            List<NebBlock> blkList = nebBlockService.findNebBlockByMiner(address.getHash(), 1, PAGE_SIZE);
            result.put("minedBlkList", convertBlock2BlockVo(blkList));
        } else {
            List<NebTransaction> txList = Lists.newLinkedList();
            if (pendingTxCnt > 0) {
                List<NebPendingTransaction> pendingTxnList = nebTransactionService.findPendingTxnByCondition(address.getHash(), 1, PAGE_SIZE);
                pendingTxnList.forEach(pTxn -> {
                    try {
                        NebTransaction tx = new NebTransaction();
                        PropertyUtils.copyProperties(tx, pTxn);
                        tx.setBlockHeight(0L);
                        txList.add(tx);
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                });
                if (pendingTxnList.size() < PAGE_SIZE) {
                    txList.addAll(nebTransactionService.findTxnByFromTo(address.getHash(), 1, PAGE_SIZE - pendingTxnList.size()));
                }
            } else {
                txList.addAll(nebTransactionService.findTxnByFromTo(address.getHash(), 1, PAGE_SIZE));
            }
            result.put("txList", txList);
        }
        return result;
    }

    private List<BlockVo> convertBlock2BlockVo(List<NebBlock> blks) {
        if (CollectionUtils.isEmpty(blks)) {
            return Collections.emptyList();
        }
        List<Long> blkHeightList = blks.stream().map(NebBlock::getHeight).collect(Collectors.toList());
        List<String> minerHashList = blks.stream().map(NebBlock::getMiner).collect(Collectors.toList());

        Map<Long, BlockSummary> txCntMap = nebTransactionService.countTxnInBlockGroupByBlockHeight(blkHeightList);
        Map<String, NebAddress> addressMap = nebAddressService.findAddressMapByAddressHash(minerHashList);

        List<BlockVo> resultList = new LinkedList<>();
        for (NebBlock blk : blks) {
            BlockVo vo = new BlockVo().build(blk);

            NebAddress nebAddress = addressMap.get(blk.getMiner());
            vo.setMiner(null == nebAddress ? new NebAddress(blk.getMiner()) : nebAddress); //in order to ensure consistent miner structure

            BlockSummary summary = txCntMap.get(blk.getHeight());
            vo.setTxnCnt(null != summary ? summary.getTxCnt() : 0L);
            if (null != summary) {
                vo.setGasLimit(summary.getGasLimit());
                vo.setGasUsed(summary.getGasUsed());
                vo.setAvgGasPrice(summary.getAvgGasPrice());
            }
            resultList.add(vo);
        }
        return resultList;
    }

    private List<TransactionVo> convertTxn2TxnVo(List<NebTransaction> txns) {
        if (CollectionUtils.isEmpty(txns)) {
            return Collections.emptyList();
        }

        Set<String> addressHashSet = Sets.newHashSet();
        txns.forEach(txn -> {
            addressHashSet.add(txn.getFrom());
            addressHashSet.add(txn.getTo());
        });
        Map<String, NebAddress> nebAddressMap = nebAddressService.findAddressMapByAddressHash(Lists.newArrayList(addressHashSet));

        List<TransactionVo> txnVoList = new LinkedList<>();
        for (NebTransaction txn : txns) {
            TransactionVo vo = new TransactionVo().build(txn);
            vo.setBlock(new BlockVo(txn.getBlockHash(), txn.getBlockHeight()));

            NebAddress fromAddress = nebAddressMap.get(txn.getFrom());
            vo.setFrom(fromAddress != null ? (new AddressVo().build(fromAddress)) : new AddressVo(txn.getFrom()));

            NebAddress toAddress = nebAddressMap.get(txn.getTo());
            vo.setTo(toAddress != null ? (new AddressVo().build(toAddress)) : new AddressVo(txn.getTo()));

            txnVoList.add(vo);
        }
        return txnVoList;
    }

    private List<TransactionVo> convertPendingTxn2TxnVo(List<NebPendingTransaction> pendingTxns) {
        if (CollectionUtils.isEmpty(pendingTxns)) {
            return Collections.emptyList();
        }

        Set<String> addressHashSet = Sets.newHashSet();
        pendingTxns.forEach(txn -> {
            addressHashSet.add(txn.getFrom());
            addressHashSet.add(txn.getTo());
        });
        Map<String, NebAddress> nebAddressMap = nebAddressService.findAddressMapByAddressHash(Lists.newArrayList(addressHashSet));

        List<TransactionVo> txnVoList = new LinkedList<>();
        for (NebPendingTransaction txn : pendingTxns) {
            TransactionVo vo = new TransactionVo().build(txn);
            NebAddress fromAddress = nebAddressMap.get(txn.getFrom());
            vo.setFrom(fromAddress != null ? (new AddressVo().build(fromAddress)) : new AddressVo(txn.getFrom()));

            NebAddress toAddress = nebAddressMap.get(txn.getTo());
            vo.setTo(toAddress != null ? (new AddressVo().build(toAddress)) : new AddressVo(txn.getTo()));

            txnVoList.add(vo);
        }
        return txnVoList;
    }
}
