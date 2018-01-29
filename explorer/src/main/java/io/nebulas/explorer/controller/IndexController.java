package io.nebulas.explorer.controller;

import io.nebulas.explorer.config.YAMLConfig;
import io.nebulas.explorer.core.BaseController;
import io.nebulas.explorer.domain.BlockSummary;
import io.nebulas.explorer.domain.NebBlock;
import io.nebulas.explorer.service.NebBlockService;
import io.nebulas.explorer.service.NebTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.joda.time.LocalDate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Title.
 * <p>
 * Description.
 *
 * @author Bill
 * @version 1.0
 * @since 2018-01-22
 */
@Slf4j
@Controller
public class IndexController extends BaseController {
    private final NebBlockService nebBlockService;
    private final NebTransactionService nebTransactionService;

    public IndexController(YAMLConfig config,
                           NebBlockService nebBlockService,
                           NebTransactionService nebTransactionService) {
        super(config);
        this.nebBlockService = nebBlockService;
        this.nebTransactionService = nebTransactionService;
    }


    /**
     * nebulas home page
     *
     * @param model
     * @return
     */
    @RequestMapping("/")
    public String index(Model model) {
        execute(model);

        List<NebBlock> blkList = nebBlockService.findNebBlockOrderByTimestamp(1, 10);
        if (CollectionUtils.isNotEmpty(blkList)) {
            List<Long> blkHeightList = blkList.stream().map(NebBlock::getHeight).collect(Collectors.toList());
            Map<Long, BlockSummary> txCntMap = nebTransactionService.countTxInBlock(blkHeightList);
            model.addAttribute("txCntMap", txCntMap);
        }
        model.addAttribute("blkList", blkList);
        model.addAttribute("txList", nebTransactionService.findTxnOrderByTimestamp(1, 10));
        model.addAttribute("historyTxCntGroup", nebTransactionService.countTxCntGroupByTimestamp(LocalDate.now().plusDays(-15).toDate(), LocalDate.now().toDate()));
        return "index";
    }

}
