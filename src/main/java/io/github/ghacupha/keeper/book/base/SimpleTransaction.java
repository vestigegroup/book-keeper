/*
 *  Copyright 2018 Edwin Njeru
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.ghacupha.keeper.book.base;

import io.github.ghacupha.keeper.book.api.Account;
import io.github.ghacupha.keeper.book.api.Entry;
import io.github.ghacupha.keeper.book.api.Transaction;
import io.github.ghacupha.keeper.book.balance.AccountSide;
import io.github.ghacupha.keeper.book.unit.money.Cash;
import io.github.ghacupha.keeper.book.unit.time.TimePoint;
import io.github.ghacupha.keeper.book.util.ImmutableEntryException;
import io.github.ghacupha.keeper.book.util.MismatchedCurrencyException;
import io.github.ghacupha.keeper.book.util.UnableToPostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

import static io.github.ghacupha.keeper.book.balance.AccountSide.CREDIT;
import static io.github.ghacupha.keeper.book.balance.AccountSide.DEBIT;

public final class SimpleTransaction implements Transaction {

    private static final Logger log = LoggerFactory.getLogger(SimpleTransaction.class);

    private final TimePoint date;

    private boolean wasPosted;
    private final Currency currency;

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    SimpleTransaction(TimePoint date, Currency currency) {

        this.date = date;
        this.currency = currency;
    }

    private static Double mapCashToDouble(Entry entry) {
        return entry.getAmount().getNumber().doubleValue();
    }

    private static boolean predicateCredits(Entry entry) {
        boolean credit;
        log.debug("Checking if entry {} is credit ", entry.getEntryDetails());
        credit = entry.getAccountSide() == CREDIT;
        log.debug("Entry : {} is credit {}", entry.getEntryDetails(), credit);
        return credit;
    }

    private static boolean predicateDebits(Entry entry) {
        boolean debit;
        log.debug("Checking if entry {} is debit ", entry.getEntryDetails());
        debit = entry.getAccountSide() == DEBIT;
        log.debug("Entry : {} is credit {}", entry.getEntryDetails(), debit);
        return debit;
    }

    /**
     * The add method adds entries to the transaction provided the transaction has not already
     * been posted
     *
     * @param amount  {@link Cash} amount being posted to the journal
     * @param account {@link Account} into which the {@link Entry} is being added
     */
    @Override
    public void addEntry(AccountSide accountSide, Cash amount, Account account, EntryDetails details) throws ImmutableEntryException, MismatchedCurrencyException {

        log.debug("Attempting to add entry {} amount of : {} in account : {} narration : {}",accountSide,amount,account,details);
        // assign currency
        if (wasPosted) {
            throw new ImmutableEntryException("Cannot add entry to a transaction that's already posted");
        } else if (!account.getCurrency().equals(this.currency) || !amount.getCurrency().equals(this.currency)) {
            throw new MismatchedCurrencyException("Cannot add entry whose getCurrency differs to that of the transaction");
        } else {
            log.debug("Adding entry  : {} into transaction : {}",details,this);
            entries.add(new SimpleEntry(accountSide, account, amount, date, details));
        }
    }

    /**
     * Posts the transactions into respective {@link Account} items
     *
     * @throws UnableToPostException {@link UnableToPostException} thrown when the transaction is not balanced
     *                               That is if the items posted on the debit are more than those posted on the credit or vice versa.
     */
    @Override
    public void post() throws UnableToPostException, ImmutableEntryException {

        if (balanced() != 0) {

            if (balanced() > 0) {

                throw new UnableToPostException(String.format("The debits are more than credits by : %s", balanced()));

            } else {

                throw new UnableToPostException(String.format("The credits are more than debits by : %s", balanced() * -1));
            }

        } else {

            log.debug("Posting : {} entries ...",entries.size());

            entries.parallelStream().forEach(Entry::post);

            wasPosted = true;
        }
    }

    private double balanced() {

        double debits = entries.parallelStream().filter(SimpleTransaction::predicateDebits).map(SimpleTransaction::mapCashToDouble).reduce(0.00, (acc, val) -> acc + val);

        return debits - entries.parallelStream().filter(SimpleTransaction::predicateCredits).map(SimpleTransaction::mapCashToDouble).reduce(0.00, (acc, val) -> acc + val);
    }

    @Override
    public Set<Entry> getEntries() {

        return Collections.unmodifiableSet(new CopyOnWriteArraySet<>(entries));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SimpleTransaction that = (SimpleTransaction) o;
        return wasPosted == that.wasPosted && Objects.equals(date, that.date) && Objects.equals(currency, that.currency) && Objects.equals(entries, that.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, wasPosted, currency, entries);
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("SimpleTransaction{");
        sb.append("date=").append(date);
        sb.append(", wasPosted=").append(wasPosted);
        sb.append(", currency=").append(currency);
        sb.append(", entries=").append(entries);
        sb.append('}');
        return sb.toString();
    }
}