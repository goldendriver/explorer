package io.nebulas.explorer.service;

import com.google.common.collect.Maps;
import io.nebulas.explorer.domain.BlockSummary;
import io.nebulas.explorer.domain.NebTransaction;
import io.nebulas.explorer.mapper.NebTransactionMapper;
import lombok.AllArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.LocalDate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * nebulas transaction related operation service
 * <p>
 * Description.
 *
 * @author nathan wang
 * @version 1.0
 * @since 2018-01-25
 */
@Service
@AllArgsConstructor
public class NebTransactionService {
    private final NebTransactionMapper nebTransactionMapper;

    /**
     * save single nebluas transaction entity
     *
     * @param transaction
     * @return
     */
    public Integer addNebTransaction(NebTransaction transaction) {
        return nebTransactionMapper.addNebTransaction(transaction);
    }

    /**
     * batch add nebluas transaction entity
     *
     * @param transactions
     * @return
     */
    public Integer batchAddNebTransaction(List<NebTransaction> transactions) {
        return nebTransactionMapper.batchAddNebTransaction(transactions);
    }

    /**
     * get nebulas transaction by hash
     *
     * @param hash
     * @return
     */
    public NebTransaction getNebTransactionByHash(String hash) {
        if (StringUtils.isEmpty(hash)) {
            return null;
        }
        return nebTransactionMapper.getByHash(hash);
    }

    public List<NebTransaction> getNebTransactionByBlockHeight(Long blockHeight) {
        return nebTransactionMapper.getByBlockHeight(blockHeight);
    }

    public int countNormalTxCntByBlockHeight(long blockHeight) {
        return nebTransactionMapper.countNormalTxCntByBlockHeight(blockHeight);
    }

    public List<NebTransaction> findNormalTxnsByBlockHeight(Long blockHeight) {
        return nebTransactionMapper.findNormalTxInBlockByBlockHeight(blockHeight);
    }

    public Map<Long, BlockSummary> countNormalTxInBlock(List<Long> blockHeights) {
        if (CollectionUtils.isEmpty(blockHeights)) {
            return Collections.emptyMap();
        }
        List<BlockSummary> blockSummaryList = nebTransactionMapper.countNormalTxInBlock(blockHeights);
        return blockSummaryList.stream().collect(Collectors.toMap(BlockSummary::getBlockHeight, summary -> summary));
    }

    public Map<String, Integer> countNormalTxnCntByFromTo(List<String> addressHashes) {
        if (CollectionUtils.isEmpty(addressHashes)) {
            return Collections.emptyMap();
        }
        List<Map<String, String>> fromList = nebTransactionMapper.countNormalTxnCntMapByFrom(addressHashes);
        Map<String, Integer> fromCntMap = fromList.stream()
                .collect(Collectors.toMap(k -> k.get("from"), v -> Integer.valueOf(v.get("cnt"))));

        List<Map<String, String>> toList = nebTransactionMapper.countNormalTxnCntMapByTo(addressHashes);
        Map<String, Integer> toCntMap = toList.stream()
                .collect(Collectors.toMap(k -> k.get("to"), v -> Integer.valueOf(v.get("cnt"))));

        return addressHashes.stream().collect(Collectors.toMap(k -> k, v -> {
            Integer fromCnt = fromCntMap.get(v);
            Integer toCnt = toCntMap.get(v);
            return (fromCnt == null ? 0 : fromCnt) + (toCnt == null ? 0 : toCnt);
        }));

    }

    public int countNormalTxnCntByFromTo(String addressHash) {
        if (StringUtils.isEmpty(addressHash)) {
            return 0;
        }
        return nebTransactionMapper.countNormalTxnCntByFromTo(addressHash);
    }

    public List<NebTransaction> findNormalTxnByFromTo(String addressHash, int page, int pageSize) {
        if (StringUtils.isEmpty(addressHash)) {
            return Collections.emptyList();
        }
        return nebTransactionMapper.findNormalTxnByFromTo(addressHash, (page - 1) * pageSize, pageSize);
    }

    public List<NebTransaction> findNormalTxnOrderByTimestamp(int page, int pageSize) {
        return nebTransactionMapper.findNormalTxnOrderByTimestamp((page - 1) * pageSize, pageSize);
    }


    public Map<String, Long> countTxCntGroupByTimestamp(Date from, Date to) {
        List<Map<String, Object>> txCntResultList = nebTransactionMapper.countTxCntGroupByTimestamp(parseDate2Str(from), parseDate2Str(to));
        if (CollectionUtils.isEmpty(txCntResultList)) {
            return Collections.emptyMap();
        }
        Map<String, Long> txCntMap = txCntResultList.stream()
                .collect(Collectors.toMap(k -> k.get("ts").toString(), v -> Long.valueOf(v.get("cnt").toString())));

        Map<String, Long> resultMap = Maps.newHashMap();
        LocalDate fromLocalDate = LocalDate.fromDateFields(from);
        LocalDate toLocalDate = LocalDate.fromDateFields(to);
        while (fromLocalDate.isBefore(toLocalDate)) {
            String dateStr = fromLocalDate.toString("yyyy-MM-dd");
            Long cnt = txCntMap.get(dateStr);
            resultMap.put(dateStr, (null != cnt ? cnt : 0));
            fromLocalDate = fromLocalDate.plusDays(1);
        }
        return resultMap;
    }

    private String parseDate2Str(Date date) {
        return LocalDate.fromDateFields(date).toString("yyyy-MM-dd");
    }
}
