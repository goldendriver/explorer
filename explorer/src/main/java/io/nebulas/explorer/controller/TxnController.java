package io.nebulas.explorer.controller;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.nebulas.explorer.config.YAMLConfig;
import io.nebulas.explorer.core.BaseController;
import io.nebulas.explorer.domain.NebPendingTransaction;
import io.nebulas.explorer.domain.NebTransaction;
import io.nebulas.explorer.enums.NebTransactionStatusEnum;
import io.nebulas.explorer.service.NebAddressService;
import io.nebulas.explorer.service.NebTransactionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Title.
 * <p>
 * Description.
 *
 * @author Bill
 * @version 1.0
 * @since 2018-01-26
 */
@Slf4j
@Controller
public class TxnController extends BaseController {
    private static final Integer PAGE_SIZE = 25;
    private final NebTransactionService nebTransactionService;
    private final NebAddressService nebAddressService;


    public TxnController(YAMLConfig config,
                         NebTransactionService nebTransactionService,
                         NebAddressService nebAddressService) {
        super(config);
        this.nebTransactionService = nebTransactionService;
        this.nebAddressService = nebAddressService;
    }

//    @RequestMapping("/txsInternal")
//    public String txsInternal(@RequestParam(value = "block", required = false) Long block, Model model) {
//        execute(model);
//
//        return "txsInternal";
//    }

    @RequestMapping("/txs")
    public String txs(@RequestParam(value = "block", required = false) Long block,
                      @RequestParam(value = "a", required = false) String address,
                      @RequestParam(value = "p", required = false, defaultValue = "1") int page,
                      Model model) {
        execute(model);

        if (page > 20) {
            page = 20;
        }

        List<NebTransaction> txnList;
        long txnCnt;
        String type;
        if (null != block) {
            txnCnt = nebTransactionService.countTxnCntByBlockHeight(block);
            txnList = nebTransactionService.findTxnByBlockHeight(block, page, PAGE_SIZE);
            type = "block";
            model.addAttribute("blkHeight", block);
        } else if (StringUtils.isNoneEmpty(address)) {
            txnCnt = nebTransactionService.countTxnCntByFromTo(address);
            txnList = nebTransactionService.findTxnByFromTo(address, page, PAGE_SIZE);
            type = "address";
            model.addAttribute("addressHash", address);
        } else {
            txnCnt = nebTransactionService.countTxnCnt();
            txnList = nebTransactionService.findTxnOrderById(page, PAGE_SIZE);
            type = "total";
        }

        Set<String> addressHashSet = Sets.newHashSet();
        txnList.forEach(txn -> {
            addressHashSet.add(txn.getFrom());
            addressHashSet.add(txn.getTo());
        });


        model.addAttribute("type", type);
        model.addAttribute("txnCnt", txnCnt);
        model.addAttribute("txnList", txnList);
        model.addAttribute("addressMap", nebAddressService.findAddressMapByAddressHash(Lists.newArrayList(addressHashSet)));
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPage", txnCnt / PAGE_SIZE + 1);
        return "txn/txs";
    }

    @RequestMapping("/tx/{txHash}")
    public String tx(@PathVariable("txHash") String txHash, Model model) {
        execute(model);

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
                model.addAttribute("pending", true);
            }
        }
        if (null == txn) {
            return "";
        }

        model.addAttribute("txn", txn);

        List<String> addressHashList = Arrays.asList(txn.getFrom(), txn.getTo());
        model.addAttribute("addressMap", nebAddressService.findAddressMapByAddressHash(addressHashList));

        return "txn/tx";
    }

    @RequestMapping("/txsPending")
    public String txsPending(@RequestParam(value = "a", required = false) String address,
                             @RequestParam(value = "p", required = false, defaultValue = "1") int page,
                             Model model) {
        execute(model);

        String type;
        long pendingTxnCnt;
        List<NebPendingTransaction> pendingTxnList;

        if (StringUtils.isEmpty(address)) {
            pendingTxnCnt = nebTransactionService.countPendingTxnCnt();
            pendingTxnList = nebTransactionService.findPendingTxnByFromTo(page, PAGE_SIZE);
            type = "total";
        } else {
            pendingTxnCnt = nebTransactionService.countPendingTxnCntByFromTo(address);
            pendingTxnList = nebTransactionService.findPendingTxnByFromTo(address, page, PAGE_SIZE);
            type = "address";
            model.addAttribute("addressHash", address);
        }

        model.addAttribute("type", type);
        model.addAttribute("address", address);
        model.addAttribute("txnTotalCnt", pendingTxnCnt);
        model.addAttribute("currentPage", page);
        model.addAttribute("txnTotalPage", pendingTxnCnt / PAGE_SIZE + 1);
        model.addAttribute("pendingTxnList", pendingTxnList);
        return "txn/txsPending";
    }

}
