package io.nebulas.explorer.controller;

import io.nebulas.explorer.config.YAMLConfig;
import io.nebulas.explorer.core.BaseController;
import io.nebulas.explorer.domain.BlockSummary;
import io.nebulas.explorer.domain.NebAddress;
import io.nebulas.explorer.domain.NebBlock;
import io.nebulas.explorer.domain.NebTransaction;
import io.nebulas.explorer.exception.NotFoundException;
import io.nebulas.explorer.model.PageIterator;
import io.nebulas.explorer.service.NebAddressService;
import io.nebulas.explorer.service.NebBlockService;
import io.nebulas.explorer.service.NebTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Title.
 * <p>
 * Description.
 *
 * @author nathan wang
 * @version 1.0
 * @since 2018-01-24
 */
@Slf4j
@Controller
@RequestMapping("")
public class BlockController extends BaseController {
    private static final Integer PAGE_SIZE = 25;
    private final NebBlockService nebBlockService;
    private final NebTransactionService nebTransactionService;
    private final NebAddressService nebAddressService;


    public BlockController(YAMLConfig config,
                           NebBlockService nebBlockService,
                           NebTransactionService nebTransactionService,
                           NebAddressService nebAddressService) {
        super(config);
        this.nebBlockService = nebBlockService;
        this.nebAddressService = nebAddressService;
        this.nebTransactionService = nebTransactionService;
    }

    /**
     * Generate block information page
     *
     * @param blkKey block hash or block height
     */
    @RequestMapping("/block/{blkKey}")
    public String detail(@PathVariable("blkKey") String blkKey, Model model) throws Exception {
        execute(model);

        NebBlock block;
        if (StringUtils.isNumeric(blkKey)) {
            block = nebBlockService.getNebBlockByHeight(Long.valueOf(blkKey));
        } else {
            block = nebBlockService.getNebBlockByHash(blkKey);
        }

        if (null == block) {
            throw new NotFoundException("neb block not found");
        }

        model.addAttribute("block", block);
        model.addAttribute("blkMaxHeight", nebBlockService.getMaxHeight());

        List<NebTransaction> txnList = nebTransactionService.findTxnByBlockHeight(block.getHeight());
        BigDecimal blkGasUsed = BigDecimal.ZERO;
        BigDecimal blkGasLimit = BigDecimal.ZERO;
        for (NebTransaction txn : txnList) {
            blkGasUsed = blkGasUsed.add(StringUtils.isEmpty(txn.getGasUsed()) ? BigDecimal.ZERO : new BigDecimal(txn.getGasUsed()));
            blkGasLimit = blkGasLimit.add(StringUtils.isEmpty(txn.getGasLimit()) ? BigDecimal.ZERO : new BigDecimal(txn.getGasLimit()));
        }
        model.addAttribute("txCnt", txnList.size());
        model.addAttribute("blkGasUsed", blkGasUsed.toPlainString());
        model.addAttribute("blkGasLimit", blkGasLimit.toPlainString());
        model.addAttribute("blkGasUsedRate", BigDecimal.ZERO.compareTo(blkGasLimit) < 0 ? blkGasUsed.divide(blkGasLimit, 2, BigDecimal.ROUND_DOWN).multiply(new BigDecimal(100)).toPlainString() : "0");

        NebAddress nebAddress = nebAddressService.getNebAddressByHash(block.getMiner());
        if (null != nebAddress) {
            model.addAttribute("miner", nebAddress);
        }
        return "block/block";
    }

    /**
     * all block list page
     *
     * @param page
     * @param model
     * @return
     */
    @RequestMapping("/blocks")
    public String blocks(@RequestParam(value = "m", required = false) String miner,
                         @RequestParam(value = "p", required = false, defaultValue = "1") int page,
                         Model model) {
        execute(model);

        PageIterator<NebBlock> blockPageIterator;
        if (StringUtils.isEmpty(miner)) {
            blockPageIterator = nebBlockService.findNebBlockPageIterator(page, PAGE_SIZE);
        } else {
            blockPageIterator = nebBlockService.findNebBlockPageIteratorByMiner(miner, page, PAGE_SIZE);
        }
        if (CollectionUtils.isNotEmpty(blockPageIterator.getData())) {
            List<Long> blkHeightList = blockPageIterator.getData().stream().map(NebBlock::getHeight).collect(Collectors.toList());
            Map<Long, BlockSummary> txCntMap = nebTransactionService.calculateTxnSummaryInBlock(blkHeightList,true);
            model.addAttribute("txCntMap", txCntMap);
        }
        model.addAttribute("miner", miner);
        model.addAttribute("blockPageIterator", blockPageIterator);
        return "block/all";
    }


}
