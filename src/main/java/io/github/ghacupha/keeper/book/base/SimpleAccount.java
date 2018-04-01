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
import io.github.ghacupha.keeper.book.balance.AccountBalance;
import io.github.ghacupha.keeper.book.balance.AccountSide;
import io.github.ghacupha.keeper.book.unit.time.DateRange;
import io.github.ghacupha.keeper.book.unit.time.SimpleDate;
import io.github.ghacupha.keeper.book.unit.time.TimePoint;
import io.github.ghacupha.keeper.book.util.ImmutableListCollector;
import io.github.ghacupha.keeper.book.util.MismatchedCurrencyException;
import io.github.ghacupha.keeper.book.util.UntimelyBookingDateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.github.ghacupha.keeper.book.balance.AccountSide.CREDIT;
import static io.github.ghacupha.keeper.book.balance.AccountSide.DEBIT;

/**
 * Implements the {@link Account} interface and maintains states for {@link Currency}, {@link AccountDetails} and
 * {@link AccountSide}. The Currency and AccountDetails are final upon initialization, but the AccountSide remains
 * volatile, inorder to represent the changing nature of the account as the {@link Entry} items are added. This is
 * also assigned on initialization allowing the client to describe default {@link AccountSide} of the {@link Account}.
 *
 * @implNote Some non-guaranteed care has been taken to make the Implementation as thread-safe as possible. This may not
 * be obviously evident by the usual use of words like "synchronized" et al. In fact synchronization would probably just
 * slow us down. Instead what has been done is that the {@link Collection} of {@link Entry} items, which is the whole
 * concept of this Account pattern, has been implemented using a {@link List} interface implementation that creates a new
 * copy of itself every time time a mutative process is carried out. It's iterator as a result is guaranteed never to throw
 * {@code ConcurrentModificationException} and it does not reflect additions, removals or changes to the list, once they
 * have been created.
 *
 * @author edwin.njeru
 */
public final class SimpleAccount implements Account {

    private static final Logger log = LoggerFactory.getLogger(SimpleAccount.class);

    private AccountAppraisalDelegate evalulationDelegate = new AccountAppraisalDelegate(this);

    private final Currency currency;
    private final AccountDetails accountDetails;
    private volatile AccountSide accountSide;

    private volatile List<Entry> entries = new CopyOnWriteArrayList<>();

    /**
     * This constructor will one day allow someone to implement the {@link List} interface with anything,
     * including a database and assign the same to this {@link Account} making this object persistent.
     * @param accountSide {@link AccountSide} to which this account belongs by default
     * @param currency {@link Currency} to be used for all {@link Entry} items to be added to this account
     * @param accountDetails {@link AccountDetails} describes the basic nature of this account from business domain's perspective
     * @param entries {@link List<Entry>} collection allowing assignment of a Collection interface for this account. One day this
     *                                   parameter will allow a dev to something like implement the list interface with a backend
     *                                   like a database or some Restful service making changes in this account persistent.
     */
    SimpleAccount(AccountSide accountSide,Currency currency,  AccountDetails accountDetails,List<Entry> entries) {
        this.currency = currency;
        this.accountSide = accountSide;
        this.accountDetails = accountDetails;
        this.entries=entries;
    }

    SimpleAccount(AccountSide accountSide,Currency currency,  AccountDetails accountDetails) {
        this.currency = currency;
        this.accountSide = accountSide;
        this.accountDetails = accountDetails;
    }

    /**
     * @param entry {@link Entry} to be added to this
     */
    @Override
    public void addEntry(Entry entry) throws MismatchedCurrencyException, UntimelyBookingDateException {

        log.debug("Adding entry to account : {}", entry);

        if (entry.getBookingDate().before(accountDetails.getOpeningDate())) {

            String message = String.format("Opening date : %s . The entry date was %s", this.accountDetails.getOpeningDate(), entry.getBookingDate());
            throw new UntimelyBookingDateException("The booking date cannot be earlier than the account opening date : " + message);

        } else if (!this.currency.equals(entry.getAmount().getCurrency())) {

            String message = String.format("Currencies mismatched :Expected getCurrency : %s but found entry denominated in %s", this.currency.toString(), entry.getAmount().getCurrency());
            throw new MismatchedCurrencyException(message);

        } else {

            entries.add(entry); // done

            log.debug("Entry : {} has been added into account : {}",entry,this);
        }
    }

    /**
     * Returns the balance of the Account
     *
     * @param asAt {@link TimePoint} at which is Effective
     * @return {@link AccountBalance}
     */
    @Override
    public AccountBalance balance(TimePoint asAt) {

        log.debug("Account balance enquiry raised as at {}, for account : {}", asAt, this);

        AccountBalance balance = evalulationDelegate.balance(new DateRange(accountDetails.getOpeningDate(), asAt));

        log.debug("Returning accounting balance for {} as at : {} as : {}", this, asAt, balance);

        return balance;
    }

    /**
     * Similar to the balance query for a given date except the date is provided through a
     * simple {@code VarArgs} int argument
     *
     * @param asAt The date as at when the {@link AccountBalance} we want is effective given
     *             in the following order
     *             i) Year
     *             ii) Month
     *             iii) Date
     * @return {@link AccountBalance} effective the date specified by the varargs
     */
    @Override
    public AccountBalance balance(int... asAt) {

        AccountBalance balance = balance(new SimpleDate(asAt[0],asAt[1],asAt[2]));

        log.debug("Returning accounting balance for {} ,as at : {} as : {}",this, Arrays.toString(asAt), balance);

        return balance;
    }

    /**
     * @return Currency of the account
     */
    @Override
    public Currency getCurrency() {
        return currency;
    }

    @Override
    public List<Entry> getEntries() {

        return new CopyOnWriteArrayList<>(entries.parallelStream().collect(ImmutableListCollector.toImmutableList()));
    }

    @Override
    public TimePoint getOpeningDate() {
        return this.accountDetails.getOpeningDate();
    }

    @Override
    public String toString() {
        return this.accountDetails.getNumber()+" "+this.accountDetails.getName();
    }

    /**
     * @return Shows the side of the balance sheet to which this belongs which could be either
     * {@link AccountSide#DEBIT} or {@link AccountSide#CREDIT}
     * @implSpec As per implementation notes this is for use only by the {@link AccountAppraisalDelegate}
     * allowing inexpensive evaluation of the {@link AccountBalance} without causing circular reference. Otherwise anyone else who needs
     * to know the {@code AccountSide} of this needs to query the {@link AccountBalance} first, and from it acquire the {@link AccountSide}.
     * Also note that the object's {@link AccountSide} is never really exposed since this implementation is returning a value based on its
     * current status.
     */
    @Override
    public AccountSide getAccountSide() {

        return this.accountSide == DEBIT ? DEBIT : CREDIT;
    }
}
