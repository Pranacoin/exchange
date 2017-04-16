/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.core.dao.blockchain;

import com.google.common.collect.ImmutableList;
import com.neemre.btcdcli4j.core.domain.Block;
import io.bisq.common.app.DevEnv;
import io.bisq.core.dao.blockchain.exceptions.BsqBlockchainException;
import io.bisq.core.dao.blockchain.exceptions.OrphanDetectedException;
import io.bisq.core.dao.blockchain.vo.*;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

// We are in threaded context. Don't mix up with UserThread.
@Slf4j
@Immutable
public class BsqParser {
    private BsqChainState bsqChainState;
    private BsqBlockchainService bsqBlockchainService;

    @Inject
    public BsqParser(BsqBlockchainService bsqBlockchainService, BsqChainState bsqChainState) {
        this.bsqBlockchainService = bsqBlockchainService;
        this.bsqChainState = bsqChainState;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Parsing with data delivered with BsqBlock list
    ///////////////////////////////////////////////////////////////////////////////////////////

    void parseBsqBlocks(List<BsqBlock> bsqBlocks,
                        int genesisBlockHeight,
                        String genesisTxId,
                        Consumer<BsqBlock> newBlockHandler)
            throws BsqBlockchainException, OrphanDetectedException {
        for (BsqBlock bsqBlock : bsqBlocks) {
            parseBsqBlock(bsqBlock,
                    genesisBlockHeight,
                    genesisTxId);
            bsqChainState.addBlock(bsqBlock);
            newBlockHandler.accept(bsqBlock);
        }
    }

    void parseBsqBlock(BsqBlock bsqBlock,
                       int genesisBlockHeight,
                       String genesisTxId)
            throws BsqBlockchainException, OrphanDetectedException {
        int blockHeight = bsqBlock.getHeight();
        log.debug("Parse block at height={} ", blockHeight);
        List<Tx> txList = new ArrayList<>(bsqBlock.getTxs());
        List<Tx> bsqTxsInBlock = new ArrayList<>();
        bsqBlock.getTxs().stream()
                .forEach(tx -> checkForGenesisTx(genesisBlockHeight, genesisTxId, blockHeight, bsqTxsInBlock, tx));
        recursiveFindBsqTxs(bsqTxsInBlock, txList, blockHeight, 0, 5300);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Parsing with data requested from bsqBlockchainService
    ///////////////////////////////////////////////////////////////////////////////////////////

    void parseBlocks(int startBlockHeight,
                     int chainHeadHeight,
                     int genesisBlockHeight,
                     String genesisTxId,
                     Consumer<BsqBlock> newBlockHandler)
            throws BsqBlockchainException, OrphanDetectedException {
        try {
            for (int blockHeight = startBlockHeight; blockHeight <= chainHeadHeight; blockHeight++) {
                Block btcdBlock = bsqBlockchainService.requestBlock(blockHeight);
                List<Tx> bsqTxsInBlock = findBsqTxsInBlock(btcdBlock,
                        genesisBlockHeight,
                        genesisTxId);
                final BsqBlock bsqBlock = new BsqBlock(ImmutableList.copyOf(bsqTxsInBlock),
                        btcdBlock.getHeight(),
                        btcdBlock.getHash(),
                        btcdBlock.getPreviousBlockHash());

                bsqChainState.addBlock(bsqBlock);
                newBlockHandler.accept(bsqBlock);
            }
        } catch (OrphanDetectedException e) {
            throw e;
        } catch (Throwable t) {
            log.error(t.toString());
            t.printStackTrace();
            throw new BsqBlockchainException(t);
        }
    }

    List<Tx> findBsqTxsInBlock(Block btcdBlock,
                               int genesisBlockHeight,
                               String genesisTxId)
            throws BsqBlockchainException, OrphanDetectedException {
        int blockHeight = btcdBlock.getHeight();
        log.debug("Parse block at height={} ", blockHeight);

        // check if the new block is the same chain we have built on.
        if (bsqChainState.isBlockConnecting(btcdBlock.getPreviousBlockHash())) {
            List<Tx> txList = new ArrayList<>();
            // We use a list as we want to maintain sorting of tx intra-block dependency
            List<Tx> bsqTxsInBlock = new ArrayList<>();
            // We add all transactions to the block
            for (String txId : btcdBlock.getTx()) {
                final Tx tx = bsqBlockchainService.requestTransaction(txId, blockHeight);
                txList.add(tx);
                checkForGenesisTx(genesisBlockHeight, genesisTxId, blockHeight, bsqTxsInBlock, tx);
            }

            // Worst case is that all txs in a block are depending on another, so only one get resolved at each iteration.
            // Min tx size is 189 bytes (normally about 240 bytes), 1 MB can contain max. about 5300 txs (usually 2000).
            // Realistically we don't expect more then a few recursive calls.
            // There are some blocks with testing such dependency chains like block 130768 where at each iteration only 
            // one get resolved.
            // Lately there is a patter with 24 iterations observed 
            recursiveFindBsqTxs(bsqTxsInBlock, txList, blockHeight, 0, 5300);

            return bsqTxsInBlock;
        } else {
            log.warn("We need to do a re-org. We got a new block which does not connect to our current chain.");
            throw new OrphanDetectedException(blockHeight);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Parse when requested from new block arrived handler (rpc) 
    ///////////////////////////////////////////////////////////////////////////////////////////

    BsqBlock parseBlock(Block btcdBlock, int genesisBlockHeight, String genesisTxId)
            throws BsqBlockchainException, OrphanDetectedException {
        List<Tx> bsqTxsInBlock = findBsqTxsInBlock(btcdBlock,
                genesisBlockHeight,
                genesisTxId);
        BsqBlock bsqBlock = new BsqBlock(ImmutableList.copyOf(bsqTxsInBlock),
                btcdBlock.getHeight(),
                btcdBlock.getHash(),
                btcdBlock.getPreviousBlockHash());
        bsqChainState.addBlock(bsqBlock);
        return bsqBlock;
    }
    
    
    ///////////////////////////////////////////////////////////////////////////////////////////
    // Generic 
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void checkForGenesisTx(int genesisBlockHeight,
                                   String genesisTxId,
                                   int blockHeight,
                                   List<Tx> bsqTxsInBlock,
                                   Tx tx) {
        if (tx.getId().equals(genesisTxId) && blockHeight == genesisBlockHeight) {
            tx.getOutputs().stream().forEach(bsqChainState::addVerifiedTxOutput);
            bsqChainState.setGenesisTx(tx);
            bsqChainState.addTx(tx);
            bsqTxsInBlock.add(tx);
        }
    }

    // Performance-wise the recursion does not hurt (e.g. 5-20 ms). 
    // The RPC requestTransaction is the bottleneck.  
    void recursiveFindBsqTxs(List<Tx> bsqTxsInBlock,
                             List<Tx> transactions,
                             int blockHeight,
                             int recursionCounter,
                             int maxRecursions) {
        // The set of txIds of txs which are used for inputs of another tx in same block
        Set<String> intraBlockSpendingTxIdSet = getIntraBlockSpendingTxIdSet(transactions);

        List<Tx> txsWithoutInputsFromSameBlock = new ArrayList<>();
        List<Tx> txsWithInputsFromSameBlock = new ArrayList<>();

        // First we find the txs which have no intra-block inputs
        outerLoop:
        for (Tx tx : transactions) {
            for (TxInput input : tx.getInputs()) {
                if (intraBlockSpendingTxIdSet.contains(input.getSpendingTxId())) {
                    // We have an input from one of the intra-block-transactions, so we cannot process that tx now.
                    // We add the tx for later parsing to the txsWithInputsFromSameBlock and move to the next tx.
                    txsWithInputsFromSameBlock.add(tx);
                    continue outerLoop;
                }
            }
            // If we have not found any tx input pointing to anther tx in the same block we add it to our
            // txsWithoutInputsFromSameBlock.
            txsWithoutInputsFromSameBlock.add(tx);
        }
        checkArgument(txsWithInputsFromSameBlock.size() + txsWithoutInputsFromSameBlock.size() == transactions.size(),
                "txsWithInputsFromSameBlock.size + txsWithoutInputsFromSameBlock.size != transactions.size");

        // Usual values is up to 25
        // There are some blocks where it seems devs have tested graphs of many depending txs, but even 
        // those dont exceed 200 recursions and are mostly old blocks from 2012 when fees have been low ;-).
        // TODO check strategy btc core uses (sorting the dependency graph would be an optimisation)
        // Seems btc core delivers tx list sorted by dependency graph. -> TODO verify and test
        if (recursionCounter > 100) {
            log.warn("Unusual high recursive calls at resolveConnectedTxs. recursionCounter=" + recursionCounter);
            log.warn("blockHeight=" + blockHeight);
            log.warn("txsWithoutInputsFromSameBlock.size " + txsWithoutInputsFromSameBlock.size());
            log.warn("txsWithInputsFromSameBlock.size " + txsWithInputsFromSameBlock.size());
            //  log.warn("txsWithInputsFromSameBlock " + txsWithInputsFromSameBlock.stream().map(e->e.getId()).collect(Collectors.toList()));
        }

        // we check if we have any valid BSQ from that tx set
        bsqTxsInBlock.addAll(txsWithoutInputsFromSameBlock.stream()
                .filter(tx -> isTxValidBsqTx(blockHeight, tx))
                .collect(Collectors.toList()));

        log.debug("Parsing of all txsWithoutInputsFromSameBlock is done.");

        // we check if we have any valid BSQ utxo from that tx set
        // We might have InputsFromSameBlock which are BTC only but not BSQ, so we cannot 
        // optimize here and need to iterate further.
        if (!txsWithInputsFromSameBlock.isEmpty()) {
            if (recursionCounter < maxRecursions) {
                recursiveFindBsqTxs(bsqTxsInBlock, txsWithInputsFromSameBlock, blockHeight,
                        ++recursionCounter, maxRecursions);
            } else {
                final String msg = "We exceeded our max. recursions for resolveConnectedTxs.\n" +
                        "txsWithInputsFromSameBlock=" + txsWithInputsFromSameBlock.toString() + "\n" +
                        "txsWithoutInputsFromSameBlock=" + txsWithoutInputsFromSameBlock;
                log.warn(msg);
                if (DevEnv.DEV_MODE)
                    throw new RuntimeException(msg);
            }
        } else {
            log.debug("We have no more txsWithInputsFromSameBlock.");
        }
    }

    private boolean isTxValidBsqTx(int blockHeight, Tx tx) {
        boolean isBsqTx = false;
        long availableValue = 0;
        for (int inputIndex = 0; inputIndex < tx.getInputs().size(); inputIndex++) {
            TxInput input = tx.getInputs().get(inputIndex);
            Optional<TxOutput> spendableTxOutput = bsqChainState.getSpendableTxOutput(input.getSpendingTxId(),
                    input.getSpendingTxOutputIndex());
            if (spendableTxOutput.isPresent()) {
                bsqChainState.addSpentTxWithSpentInfo(spendableTxOutput.get(), new SpentInfo(blockHeight, tx.getId(), inputIndex));
                availableValue = availableValue + spendableTxOutput.get().getValue();
            }
        }

        // If we have an input with BSQ we iterate the outputs
        if (availableValue > 0) {
            bsqChainState.addTx(tx);
            isBsqTx = true;

            // We use order of output index. An output is a BSQ utxo as long there is enough input value
            for (TxOutput txOutput : tx.getOutputs()) {
                final long txOutputValue = txOutput.getValue();
                if (availableValue >= txOutputValue) {
                    // We are spending available tokens
                    bsqChainState.addVerifiedTxOutput(txOutput);
                    availableValue -= txOutputValue;
                    if (availableValue == 0) {
                        log.debug("We don't have anymore BSQ to spend");
                        break;
                    }
                } else {
                    break;
                }
            }

            if (availableValue > 0) {
                log.debug("BSQ have been left which was not spent. Burned BSQ amount={}, tx={}",
                        availableValue,
                        tx.toString());
                bsqChainState.addBurnedFee(tx.getId(), availableValue);
            }
        }

        return isBsqTx;
    }

    private Set<String> getIntraBlockSpendingTxIdSet(List<Tx> transactions) {
        Set<String> txIdSet = transactions.stream().map(Tx::getId).collect(Collectors.toSet());
        Set<String> intraBlockSpendingTxIdSet = new HashSet<>();
        transactions.stream()
                .forEach(tx -> tx.getInputs().stream()
                        .filter(input -> txIdSet.contains(input.getSpendingTxId()))
                        .forEach(input -> intraBlockSpendingTxIdSet.add(input.getSpendingTxId())));
        return intraBlockSpendingTxIdSet;
    }
}