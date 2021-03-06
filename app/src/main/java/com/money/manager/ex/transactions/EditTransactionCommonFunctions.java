/*
 * Copyright (C) 2012-2016 The Android Money Manager Ex Project Team
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.money.manager.ex.transactions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.content.ContextCompat;
import android.text.Editable;
import android.text.Html;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.codetroopers.betterpickers.calendardatepicker.CalendarDatePickerDialogFragment;
import com.money.manager.ex.Constants;
import com.money.manager.ex.PayeeActivity;
import com.money.manager.ex.R;
import com.money.manager.ex.account.AccountListActivity;
import com.money.manager.ex.common.AmountInputDialog;
import com.money.manager.ex.common.CommonSplitCategoryLogic;
import com.money.manager.ex.core.UIHelper;
import com.money.manager.ex.database.ISplitTransaction;
import com.money.manager.ex.database.ITransactionEntity;
import com.money.manager.ex.datalayer.CategoryRepository;
import com.money.manager.ex.datalayer.IRepository;
import com.money.manager.ex.datalayer.PayeeRepository;
import com.money.manager.ex.datalayer.SubcategoryRepository;
import com.money.manager.ex.domainmodel.Category;
import com.money.manager.ex.domainmodel.Subcategory;
import com.money.manager.ex.servicelayer.AccountService;
import com.money.manager.ex.common.BaseFragmentActivity;
import com.money.manager.ex.common.CategoryListActivity;
import com.money.manager.ex.core.Core;
import com.money.manager.ex.core.TransactionTypes;
import com.money.manager.ex.currency.CurrencyService;
import com.money.manager.ex.datalayer.AccountRepository;
import com.money.manager.ex.datalayer.AccountTransactionRepository;
import com.money.manager.ex.domainmodel.Account;
import com.money.manager.ex.domainmodel.Payee;
import com.money.manager.ex.settings.AppSettings;
import com.money.manager.ex.utils.MmxDateTimeUtils;
import com.shamanland.fonticon.FontIconView;
import com.squareup.sqlbrite.BriteDatabase;

import org.joda.time.DateTime;
import org.parceler.Parcels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import info.javaperformance.money.Money;
import info.javaperformance.money.MoneyFactory;
import timber.log.Timber;

/**
 * Functions shared between Checking Account activity and Recurring Transactions activity.
 */
public class EditTransactionCommonFunctions {

    private static final int REQUEST_PICK_PAYEE = 1;
    private static final int REQUEST_PICK_ACCOUNT = 2;
    private static final int REQUEST_PICK_CATEGORY = 3;
    private static final int REQUEST_PICK_SPLIT_TRANSACTION = 4;

    private static final String DATEPICKER_TAG = "datepicker";

    public EditTransactionCommonFunctions(BaseFragmentActivity parentActivity,
                                          ITransactionEntity transactionEntity, BriteDatabase database) {
        super();

        mContext = parentActivity.getApplicationContext();
        mParent = parentActivity;
        this.transactionEntity = transactionEntity;
        this.mDatabase = database;
    }

    // Model
    public ITransactionEntity transactionEntity;
    public String payeeName;
    public String mToAccountName;
    public String categoryName, subCategoryName;
    public ArrayList<ISplitTransaction> mSplitTransactions;
    public ArrayList<ISplitTransaction> mSplitTransactionsDeleted;

    // Controls
    public EditTransactionViewHolder viewHolder;

    // Other

    private Context mContext;
    private BaseFragmentActivity mParent;
    private boolean mSplitSelected;
    private boolean mDirty = false; // indicate whether the data has been modified by the user.
    private String mSplitCategoryEntityName;
    private BriteDatabase mDatabase;

    private List<Account> AccountList;
    private ArrayList<String> mAccountNameList = new ArrayList<>();
    private ArrayList<Integer> mAccountIdList = new ArrayList<>();
    private TransactionTypes previousTransactionType = TransactionTypes.Withdrawal;
    private String[] mStatusItems, mStatusValues;    // arrays to manage trans.code and status

    public boolean deleteMarkedSplits(IRepository repository) {
        for (int i = 0; i < mSplitTransactionsDeleted.size(); i++) {
            ISplitTransaction splitToDelete = mSplitTransactionsDeleted.get(i);

            // Ignore unsaved entities.
            if (!splitToDelete.hasId()) continue;

            if (!repository.delete(splitToDelete)) {
                Toast.makeText(getContext(), R.string.db_checking_update_failed, Toast.LENGTH_SHORT).show();
                Timber.w("Delete split transaction failed!");
                return false;
            }
        }

        return true;
    }

    public void displayCategoryName() {
        // validation
        if (this.viewHolder.categoryTextView == null) return;

        this.viewHolder.categoryTextView.setText("");

        if (isSplitSelected()) {
            // Split transaction. Show ...
            this.viewHolder.categoryTextView.setText("\u2026");
        } else {
            if (!TextUtils.isEmpty(categoryName)) {
                this.viewHolder.categoryTextView.setText(categoryName);
                if (!TextUtils.isEmpty(subCategoryName)) {
                    this.viewHolder.categoryTextView.setText(Html.fromHtml(
                            this.viewHolder.categoryTextView.getText() + " : <i>" + subCategoryName + "</i>"));
                }
            }
        }
    }

    public void findControls(Activity view) {
        this.viewHolder = new EditTransactionViewHolder(view);
    }

    public Context getContext() {
        return mContext;
    }

    public Integer getAccountCurrencyId(int accountId) {
        if (accountId == Constants.NOT_SET) return Constants.NOT_SET;

        AccountRepository repo = new AccountRepository(getContext());
        Integer currencyId = repo.loadCurrencyIdFor(accountId);
        if (currencyId == null) {
            new UIHelper(getContext()).showToast(R.string.error_loading_currency);

            currencyId = Constants.NOT_SET;
        }
        return currencyId;
    }

    public String getTransactionType() {
        if (this.transactionEntity.getTransactionType() == null) {
            return null;
        }

        return transactionEntity.getTransactionType().name();
    }

    public FontIconView getDepositButtonIcon() {
        return (FontIconView) mParent.findViewById(R.id.depositButtonIcon);
    }

    public Integer getDestinationCurrencyId() {
        Integer accountId = this.transactionEntity.getAccountToId();
        // The destination account/currency is hidden by default and may be uninitialized.
        if (!transactionEntity.hasAccountTo() && !mAccountIdList.isEmpty()) {
            accountId = mAccountIdList.get(0);
        }

        // Handling some invalid values.
        if (accountId == null || accountId == 0) accountId = Constants.NOT_SET;

        return getAccountCurrencyId(accountId);
    }

    public ArrayList<ISplitTransaction> getDeletedSplitCategories() {
        if(mSplitTransactionsDeleted == null){
            mSplitTransactionsDeleted = new ArrayList<>();
        }
        return mSplitTransactionsDeleted;
    }

    public boolean getDirty() {
        return mDirty;
    }

    public Integer getSourceCurrencyId() {
        Integer accountId = this.transactionEntity.getAccountId();

        //if (!transactionEntity.has)
        if (accountId == null && !mAccountIdList.isEmpty()) {
            accountId = mAccountIdList.get(0);
        }

        if (accountId == null || accountId == 0) accountId = Constants.NOT_SET;

        return getAccountCurrencyId(accountId);
    }

    public FontIconView getTransferButtonIcon() {
        return (FontIconView) mParent.findViewById(R.id.transferButtonIcon);
    }

    public FontIconView getWithdrawalButtonIcon() {
        return (FontIconView) mParent.findViewById(R.id.withdrawalButtonIcon);
    }

    public boolean hasPayee() {
        return this.transactionEntity.getPayeeId() > 0;
    }

    public boolean hasSplitCategories() {
        return !getSplitTransactions().isEmpty();
    }

    /**
     * Initialize account selectors.
     */
    public void initAccountSelectors() {
        AppSettings settings = new AppSettings(getContext());

        // Account list as the data source to populate the drop-downs.

        AccountService accountService = new AccountService(getContext());
        this.AccountList = accountService.getTransactionAccounts(
                settings.getLookAndFeelSettings().getViewOpenAccounts(),
                settings.getLookAndFeelSettings().getViewFavouriteAccounts());
        if (this.AccountList == null) return;

        for(Account account : this.AccountList) {
            mAccountNameList.add(account.getName());
            mAccountIdList.add(account.getId());
        }

        AccountRepository accountRepository = new AccountRepository(getContext());
        Integer accountId = transactionEntity.getAccountId();
        if (accountId != null) {
            addMissingAccountToSelectors(accountRepository, accountId);
        }
        addMissingAccountToSelectors(accountRepository, transactionEntity.getAccountToId());
        // add the default account, if any.
        Integer defaultAccount = settings.getGeneralSettings().getDefaultAccountId();
        // Set the current account, if not set already.
        if ((accountId != null && accountId == Constants.NOT_SET) && (defaultAccount != null && defaultAccount != Constants.NOT_SET)) {
            accountId = defaultAccount;
            addMissingAccountToSelectors(accountRepository, accountId);
            // Set the default account as the active account.
            transactionEntity.setAccountId(accountId);
        }

        // Adapter for account selectors.

        ArrayAdapter<String> accountAdapter = new ArrayAdapter<>(mParent,
                android.R.layout.simple_spinner_item, mAccountNameList);

        accountAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        viewHolder.spinAccount.setAdapter(accountAdapter);
        viewHolder.spinAccountTo.setAdapter(accountAdapter);

        // Selection handler.

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if ((position < 0) || (position > mAccountIdList.size())) {
                    return;
                }

                setDirty(true);

                boolean isSource = parent == viewHolder.spinAccount;
                boolean isTransfer = transactionEntity.getTransactionType() == TransactionTypes.Transfer;
                Integer accountId = mAccountIdList.get(position);

                if (isSource) {
                    int originalCurrencyId = getSourceCurrencyId();

                    transactionEntity.setAccountId(accountId);

                    if (isTransfer) {
                        // calculate the exchange amount if it is 0.
                        if (transactionEntity.getAmountTo().isZero()) {
                            Money convertedAmount = calculateAmountTo();
                            transactionEntity.setAmountTo(convertedAmount);
                            displayAmountTo();
                        }
                        // Recalculate the original amount when the currency changes.
                        if (originalCurrencyId != getSourceCurrencyId()) {
                            Money exchangeAmount = calculateAmountFrom();
                            transactionEntity.setAmount(exchangeAmount);
                            displayAmountFrom();
                        }
                    } else {
                        displayAmountFrom();
                    }
                } else {
                    int originalCurrencyId = getDestinationCurrencyId();

                    transactionEntity.setAccountToId(accountId);

                    if (isTransfer) {
                        // calculate the exchange amount if it is 0.
                        if (transactionEntity.getAmount().isZero()) {
                            Money convertedAmount = calculateAmountFrom();
                            transactionEntity.setAmount(convertedAmount);
                            displayAmountFrom();
                        }
                        // Recalculate the original amount when the currency changes.
                        if (originalCurrencyId != getDestinationCurrencyId()) {
                            Money exchangeAmount = calculateAmountTo();
                            transactionEntity.setAmountTo(exchangeAmount);
                            displayAmountTo();
                        }
                    } else {
                        displayAmountTo();
                    }
                }

                refreshControlTitles();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };

        // Account

        int accountIndex = mAccountIdList.indexOf(accountId);
        if (accountIndex >= 0) {
            viewHolder.spinAccount.setSelection(accountIndex, true);
        }
        viewHolder.spinAccount.setOnItemSelectedListener(listener);

        // To Account

        if (transactionEntity.hasAccountTo() && mAccountIdList.indexOf(transactionEntity.getAccountToId()) >= 0) {
            viewHolder.spinAccountTo.setSelection(mAccountIdList.indexOf(transactionEntity.getAccountToId()), true);
        }
        viewHolder.spinAccountTo.setOnItemSelectedListener(listener);
    }

    public void initAmountSelectors() {
        View.OnClickListener onClickAmount = new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                // Get currency id from the account for which the amount has been modified.
                Integer currencyId;
                Money amount;

                if (v.equals(viewHolder.txtAmountTo)) {
                    // clicked Amount To.
                    currencyId = getDestinationCurrencyId();
                    amount = transactionEntity.getAmountTo();
                } else {
                    // clicked Amount.
                    currencyId = getSourceCurrencyId();
                    amount = transactionEntity.getAmount();
                }

                AmountInputDialog dialog = AmountInputDialog.getInstance(v.getId(), amount, currencyId);
                dialog.show(mParent.getSupportFragmentManager(), dialog.getClass().getSimpleName());

                // The result is received in onFinishedInputAmountDialog.
            }
        };

        // amount
        displayAmountFrom();
        viewHolder.txtAmount.setOnClickListener(onClickAmount);

        // amount to
        displayAmountTo();
        viewHolder.txtAmountTo.setOnClickListener(onClickAmount);
    }

    /**
     * Initialize Category selector.
     * @param datasetName name of the dataset (TableBudgetSplitTransactions.class.getSimpleName())
     */
    public void initCategoryControls(final String datasetName) {
        // keep the dataset name for later.
        this.mSplitCategoryEntityName = datasetName;

        this.viewHolder.categoryTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isSplitSelected()) {
                    // select first category.
                    Intent intent = new Intent(mParent, CategoryListActivity.class);
                    intent.setAction(Intent.ACTION_PICK);
                    mParent.startActivityForResult(intent, REQUEST_PICK_CATEGORY);
                } else {
                    // select split categories.
                    showSplitCategoriesForm(mSplitCategoryEntityName);
                }

                // results are handled in onActivityResult.
            }
        });
    }

    /**
     * Due Date picker
     */
    public void initDateSelector() {
        DateTime date = this.transactionEntity.getDate();
        if (date == null) {
            date = DateTime.now();
            transactionEntity.setDate(date);
        }
        showDate(date);

        viewHolder.dateTextView.setOnClickListener(new View.OnClickListener() {
            CalendarDatePickerDialogFragment.OnDateSetListener listener = new CalendarDatePickerDialogFragment.OnDateSetListener() {
                @Override
                public void onDateSet(CalendarDatePickerDialogFragment dialog, int year, int monthOfYear, int dayOfMonth) {
                    DateTime dateTime = MmxDateTimeUtils.from(year, monthOfYear + 1, dayOfMonth);
                    setDate(dateTime);
                }
            };

            @Override
            public void onClick(View v) {
                DateTime dateTime = transactionEntity.getDate();

                CalendarDatePickerDialogFragment datePicker = new CalendarDatePickerDialogFragment()
                    .setOnDateSetListener(listener)
                    .setFirstDayOfWeek(MmxDateTimeUtils.getFirstDayOfWeek())
                    .setPreselectedDate(dateTime.getYear(), dateTime.getMonthOfYear() - 1, dateTime.getDayOfMonth());
                if (new UIHelper(getContext()).isDarkTheme()) {
                    datePicker.setThemeDark();
                }
                datePicker.show(mParent.getSupportFragmentManager(), DATEPICKER_TAG);
            }
        });

        viewHolder.previousDayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                DateTime dateTime = transactionEntity.getDate().minusDays(1);
                setDate(dateTime);
            }
        });

        viewHolder.nextDayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                DateTime dateTime = transactionEntity.getDate().plusDays(1);
                setDate(dateTime);
            }
        });
    }

    public void initNotesControls() {
        viewHolder.edtNotes = (EditText) mParent.findViewById(R.id.editTextNotes);
        if (!(TextUtils.isEmpty(transactionEntity.getNotes()))) {
            viewHolder.edtNotes.setText(transactionEntity.getNotes());
        }

        viewHolder.edtNotes.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                setDirty(true);
                transactionEntity.setNotes(editable.toString());
            }
        });
    }

    public void initPayeeControls() {
        this.viewHolder.txtSelectPayee.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mParent, PayeeActivity.class);
                intent.setAction(Intent.ACTION_PICK);
                mParent.startActivityForResult(intent, REQUEST_PICK_PAYEE);

                // the result is handled in onActivityResult
            }
        });

        viewHolder.removePayeeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setDirty(true);

                transactionEntity.setPayeeId(Constants.NOT_SET);
                payeeName = "";

                showPayeeName();
            }
        });
    }

    /**
     * Initialize Split Categories button & controls.
     */
    public void initSplitCategories() {
        viewHolder.splitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean splitting = !isSplitSelected();

                if (splitting) {
                    showSplitCategoriesForm(mSplitCategoryEntityName);
                } else {
                    // User wants to remove split.
                    int splitCount = getSplitTransactions().size();
                    switch (splitCount) {
                        case 0:
                            // just remove split
                            setSplit(false);
                            break;
                        case 1:
                            convertOneSplitIntoRegularTransaction();
                            break;
                        default:
                            showSplitResetNotice();
                            break;
                    }
                }
            }
        });

        refreshSplitControls();
    }

    public void initStatusSelector() {
        mStatusItems = mContext.getResources().getStringArray(R.array.status_items);
        mStatusValues = mContext.getResources().getStringArray(R.array.status_values);

        // create adapter for spinnerStatus
        ArrayAdapter<String> adapterStatus = new ArrayAdapter<>(mParent,
                android.R.layout.simple_spinner_item, mStatusItems);
        adapterStatus.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        viewHolder.spinStatus.setAdapter(adapterStatus);

        // select current value
        if (!(TextUtils.isEmpty(transactionEntity.getStatus()))) {
            if (Arrays.asList(mStatusValues).indexOf(transactionEntity.getStatus()) >= 0) {
                viewHolder.spinStatus.setSelection(Arrays.asList(mStatusValues).indexOf(transactionEntity.getStatus()), true);
            }
        } else {
            transactionEntity.setStatus(mStatusValues[0]);
        }
        viewHolder.spinStatus.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if ((position >= 0) && (position <= mStatusValues.length)) {
                    String selectedStatus = mStatusValues[position];
                    // If Status has been changed manually, mark data as dirty.
                    if (!selectedStatus.equalsIgnoreCase(transactionEntity.getStatus())) {
                        setDirty(true);
                    }
                    transactionEntity.setStatus(selectedStatus);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    public void initTransactionNumberControls() {
        // Transaction number

        viewHolder.edtTransNumber = (EditText) mParent.findViewById(R.id.editTextTransNumber);
        if (!TextUtils.isEmpty(transactionEntity.getTransactionNumber())) {
            viewHolder.edtTransNumber.setText(transactionEntity.getTransactionNumber());
        }

        // e change
        viewHolder.edtTransNumber.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

            }

            @Override
            public void afterTextChanged(Editable editable) {
                setDirty(true);

                transactionEntity.setTransactionNumber(editable.toString());
            }
        });

        viewHolder.btnTransNumber = (ImageButton) mParent.findViewById(R.id.buttonTransNumber);
        viewHolder.btnTransNumber.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AccountTransactionRepository repo = new AccountTransactionRepository(getContext());

                String sql = "SELECT MAX(CAST(" + ITransactionEntity.TRANSACTIONNUMBER + " AS INTEGER)) FROM " +
                    repo.getSource() + " WHERE " +
                    ITransactionEntity.ACCOUNTID + "=?";

//                Cursor cursor = mOpenHelper.getReadableDatabase().rawQuery(sql,
//                    new String[]{Integer.toString(transactionEntity.getAccountId())});
                String accountId = transactionEntity.getAccountId().toString();
                Cursor cursor = mDatabase.query(sql, accountId);
                if (cursor == null) return;

                if (cursor.moveToFirst()) {
                    String transNumber = cursor.getString(0);
                    if (TextUtils.isEmpty(transNumber)) {
                        transNumber = "0";
                    }
                    if ((!TextUtils.isEmpty(transNumber)) && TextUtils.isDigitsOnly(transNumber)) {
                        try {
                            Money transactionNumber = MoneyFactory.fromString(transNumber);
                            viewHolder.edtTransNumber.setText(transactionNumber.add(MoneyFactory.fromString("1"))
                                .toString());
                        } catch (Exception e) {
                            Timber.e(e, "adding transaction number");
                        }
                    }
                }
                cursor.close();
            }
        });
    }

    public void initTransactionTypeSelector() {
        // Handle click events.
        View.OnClickListener onClickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setDirty(true);

                // find which transaction type this is.
                TransactionTypes type = (TransactionTypes) v.getTag();
                changeTransactionTypeTo(type);
            }
        };

        if (viewHolder.withdrawalButton != null) {
            viewHolder.withdrawalButton.setTag(TransactionTypes.Withdrawal);

            viewHolder.withdrawalButton.setOnClickListener(onClickListener);
        }
        if (viewHolder.depositButton != null) {
            viewHolder.depositButton.setTag(TransactionTypes.Deposit);

            viewHolder.depositButton.setOnClickListener(onClickListener);
        }
        if (viewHolder.transferButton != null) {
            viewHolder.transferButton.setTag(TransactionTypes.Transfer);

            viewHolder.transferButton.setOnClickListener(onClickListener);
        }

        // Check if the transaction type has been set (for example, when editing an existing transaction).
        TransactionTypes current = transactionEntity.getTransactionType() == null
                ? TransactionTypes.Withdrawal
                : transactionEntity.getTransactionType();
        changeTransactionTypeTo(current);
    }

    /**
     * Indicate whether the Split Categories is selected/checked.
     * @return boolean
     */
    public boolean isSplitSelected() {
        return mSplitSelected;
    }

    /**
     * Loads info for Category and Subcategory
     * @return A boolean indicating whether the operation was successful.
     */
    public boolean loadCategoryName() {
        if(!this.transactionEntity.hasCategory() && this.transactionEntity.getSubcategoryId() <= 0) return false;

        CategoryRepository categoryRepository = new CategoryRepository(getContext());
        Category category = categoryRepository.load(this.transactionEntity.getCategoryId());
        if (category != null) {
            this.categoryName = category.getName();
        } else {
            this.categoryName = null;
        }

        SubcategoryRepository subRepo = new SubcategoryRepository(getContext());
        Subcategory subcategory = subRepo.load(this.transactionEntity.getSubcategoryId());
        if (subcategory != null) {
            this.subCategoryName = subcategory.getName();
        } else {
            this.subCategoryName = null;
        }

        return true;
    }

    public boolean onActionCancelClick() {
        if (getDirty()) {
            final MaterialDialog dialog = new MaterialDialog.Builder(mParent)
                .title(android.R.string.cancel)
                .content(R.string.transaction_cancel_confirm)
                .positiveText(R.string.discard)
                .negativeText(R.string.keep_editing)
                .cancelable(false)
                .onPositive(new MaterialDialog.SingleButtonCallback() {
                    @Override
                    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                        cancelActivity();
                    }
                })
                .build();
            dialog.show();
        } else {
            // Just close activity
            cancelActivity();
        }
        return true;
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case EditTransactionCommonFunctions.REQUEST_PICK_PAYEE:
                if ((resultCode != Activity.RESULT_OK) || (data == null)) return;

                setDirty(true);

                this.transactionEntity.setPayeeId(data.getIntExtra(PayeeActivity.INTENT_RESULT_PAYEEID, Constants.NOT_SET));
                payeeName = data.getStringExtra(PayeeActivity.INTENT_RESULT_PAYEENAME);
                // select last category used from payee. Only if category has not been entered earlier.
                if (!isSplitSelected() && !this.transactionEntity.hasCategory() ) {
                    if (setCategoryFromPayee(this.transactionEntity.getPayeeId())) {
                        displayCategoryName(); // refresh UI
                    }
                }
                // refresh UI
                showPayeeName();
                break;

            case EditTransactionCommonFunctions.REQUEST_PICK_ACCOUNT:
                if ((resultCode != Activity.RESULT_OK) || (data == null)) return;

                setDirty(true);

                transactionEntity.setAccountToId(data.getIntExtra(AccountListActivity.INTENT_RESULT_ACCOUNTID, Constants.NOT_SET));
                mToAccountName = data.getStringExtra(AccountListActivity.INTENT_RESULT_ACCOUNTNAME);
                break;

            case EditTransactionCommonFunctions.REQUEST_PICK_CATEGORY:
                if ((resultCode != Activity.RESULT_OK) || (data == null)) return;

                setDirty(true);

                this.transactionEntity.setCategoryId(data.getIntExtra(CategoryListActivity.INTENT_RESULT_CATEGID, Constants.NOT_SET));
                categoryName = data.getStringExtra(CategoryListActivity.INTENT_RESULT_CATEGNAME);
                this.transactionEntity.setSubcategoryId(data.getIntExtra(CategoryListActivity.INTENT_RESULT_SUBCATEGID, Constants.NOT_SET));
                subCategoryName = data.getStringExtra(CategoryListActivity.INTENT_RESULT_SUBCATEGNAME);
                // refresh UI category
                displayCategoryName();
                break;

            case EditTransactionCommonFunctions.REQUEST_PICK_SPLIT_TRANSACTION:
                if ((resultCode != Activity.RESULT_OK) || (data == null)) break;

                setDirty(true);

                mSplitTransactions = Parcels.unwrap(data.getParcelableExtra(SplitCategoriesActivity.INTENT_RESULT_SPLIT_TRANSACTION));

                // deleted items
                Parcelable parcelDeletedSplits = data.getParcelableExtra(SplitCategoriesActivity.INTENT_RESULT_SPLIT_TRANSACTION_DELETED);
                if (parcelDeletedSplits != null) {
                    mSplitTransactionsDeleted = Parcels.unwrap(parcelDeletedSplits);
                }

                // Splits and deleted splits must be restored before any action takes place.
                onSplitConfirmed(getSplitTransactions());

                break;
        }
    }

    public void onFinishedInputAmountDialog(int id, Money amount) {
        View view = mParent.findViewById(id);
        if (view == null || !(view instanceof TextView)) return;

        setDirty(true);

        boolean isTransfer = transactionEntity.getTransactionType().equals(TransactionTypes.Transfer);
        boolean isAmountFrom = id == R.id.textViewAmount;

        // Set and display the selected amount.
        if (isAmountFrom) {
            this.transactionEntity.setAmount(amount);
            displayAmountFrom();
        } else {
            this.transactionEntity.setAmountTo(amount);
            displayAmountTo();
        }

        // Handle currency exchange on Transfers.
        if (isTransfer) {
            Integer fromCurrencyId = getSourceCurrencyId();
            Integer toCurrencyId = getDestinationCurrencyId();
            if (fromCurrencyId.equals(toCurrencyId)) {
                // Same currency. Update both values if the transfer is in the same currency.
                this.transactionEntity.setAmount(amount);
                this.transactionEntity.setAmountTo(amount);

                displayAmountFrom();
                displayAmountTo();
                // Exit here.
                return;
            }

            // Different currency. Recalculate the other amount only if it has not been set.
            boolean shouldConvert = isAmountFrom
                    ? transactionEntity.getAmountTo().isZero()
                    : transactionEntity.getAmount().isZero();
            if (shouldConvert){
                // Convert the value and write the amount into the other input box.
                Money convertedAmount;
                if (isAmountFrom) {
                    convertedAmount = calculateAmountTo();
                    transactionEntity.setAmountTo(convertedAmount);
                    displayAmountTo();
                } else {
                    convertedAmount = calculateAmountFrom();
                    transactionEntity.setAmount(convertedAmount);
                    displayAmountFrom();
                }
            }
        }
    }

    /**
     * Handle the controls after the split is checked.
     */
    public void refreshSplitControls() {
        // display category field
        displayCategoryName();

        // enable/disable Amount field.
        viewHolder.txtAmount.setEnabled(!mSplitSelected);
        viewHolder.txtAmountTo.setEnabled(!mSplitSelected);

        updateSplitButton();
    }

    /**
     * Reflect the transaction type change. Show and hide controls appropriately.
     */
    public void onTransactionTypeChanged(TransactionTypes transactionType) {
        transactionEntity.setTransactionType(transactionType);

        boolean isTransfer = transactionType.equals(TransactionTypes.Transfer);

        viewHolder.accountFromLabel.setText(isTransfer ? R.string.from_account : R.string.account);
        viewHolder.tableRowAccountTo.setVisibility(isTransfer ? View.VISIBLE : View.GONE);
        viewHolder.tableRowPayee.setVisibility(!isTransfer ? View.VISIBLE : View.GONE);
        viewHolder.tableRowAmountTo.setVisibility(isTransfer ? View.VISIBLE : View.GONE);

        refreshControlTitles();

        if (isTransfer) {
            onTransferSelected();
        } else {
            // Change sign for the split records. Transfers should delete split records.
            CommonSplitCategoryLogic.changeSign(getSplitTransactions());
        }
    }

    /**
     * Update input control titles to reflect the transaction type.
     */
    public void refreshControlTitles() {
        if (viewHolder.amountHeaderTextView == null || viewHolder.amountToHeaderTextView == null) return;

        boolean isTransfer = transactionEntity.getTransactionType().equals(TransactionTypes.Transfer);

        if (!isTransfer) {
            viewHolder.amountHeaderTextView.setText(R.string.amount);
        } else {
            // Transfer. Adjust the headers on amount text boxes.
            int index = mAccountIdList.indexOf(transactionEntity.getAccountId());
            if (index >= 0) {
                // the title depends on whether we are showing the destination amount.
                if (areCurrenciesSame()) {
                    viewHolder.amountHeaderTextView.setText(getContext().getString(R.string.transfer_amount));
                } else {
                    viewHolder.amountHeaderTextView.setText(mParent.getString(R.string.withdrawal_from,
                            this.AccountList.get(index).getName()));
                }
            }

            index = mAccountIdList.indexOf(transactionEntity.getAccountToId());
            if (index >= 0) {
                viewHolder.amountToHeaderTextView.setText(mParent.getString(R.string.deposit_to,
                        this.AccountList.get(index).getName()));
            }
        }
    }

    /**
     * update UI interface with PayeeName
     */
    public void showPayeeName() {
        // write into text button payee name
        if (this.viewHolder.txtSelectPayee != null) {
            String text = !TextUtils.isEmpty(payeeName)
                    ? payeeName : "";

            this.viewHolder.txtSelectPayee.setText(text);
        }
    }

    /**
     * Reset the effects of transfer when switching to Withdrawal/Deposit.
     */
    public void resetTransfer() {
        // reset destination account and amount
        transactionEntity.setAccountToId(Constants.NOT_SET);
        transactionEntity.setAmountTo(MoneyFactory.fromDouble(0));
    }

    public void setSplit(boolean checked) {
        mSplitSelected = checked;

        refreshSplitControls();
    }

    /**
     * query info payee
     * @param payeeId id payee
     * @return true if the data selected
     */
    public boolean loadPayeeName(int payeeId) {
        PayeeRepository repo = new PayeeRepository(getContext());
        Payee payee = repo.load(payeeId);
        if (payee != null) {
            this.payeeName = payee.getName();
        } else {
            this.payeeName = "";
        }

        return true;
    }

    /**
     * setCategoryFromPayee set last category used from payee
     * @param payeeId Identify of payee
     * @return true if category set
     */
    public boolean setCategoryFromPayee(int payeeId) {
        if (payeeId == Constants.NOT_SET) return false;

        PayeeRepository repo = new PayeeRepository(getContext());
        Payee payee = repo.load(payeeId);
        if (payee == null) return false;
        if (!payee.hasCategory()) return false;

        // otherwise

        this.transactionEntity.setCategoryId(payee.getCategoryId());
        this.transactionEntity.setSubcategoryId(payee.getSubcategoryId());

        loadCategoryName();

        return true;
    }

    public void setDirty(boolean dirty) {
        mDirty = dirty;
    }

    /**
     * Select, or change, the type of transaction (withdrawal, deposit, transfer).
     * Entry point and the handler for the type selector input control.
     * @param transactionType The type to set the transaction to.
     */
    public void changeTransactionTypeTo(TransactionTypes transactionType) {
        this.previousTransactionType = this.transactionEntity.getTransactionType();
        this.transactionEntity.setTransactionType(transactionType);

        // Clear all buttons.

        Core core = new Core(mContext);
        int backgroundInactive = core.getColourFromAttribute(R.attr.button_background_inactive);

        viewHolder.withdrawalButton.setBackgroundColor(backgroundInactive);
        getWithdrawalButtonIcon().setTextColor(ContextCompat.getColor(mContext, R.color.material_red_700));
        viewHolder.depositButton.setBackgroundColor(backgroundInactive);
        getDepositButtonIcon().setTextColor(ContextCompat.getColor(mContext, R.color.material_green_700));
        viewHolder.transferButton.setBackgroundColor(backgroundInactive);
        getTransferButtonIcon().setTextColor(ContextCompat.getColor(mContext, R.color.material_grey_700));

        // Style the selected button.

        int backgroundSelected = ContextCompat.getColor(mParent, R.color.button_background_active);
        int foregroundSelected = ContextCompat.getColor(mContext, R.color.button_foreground_active);

        switch (transactionType) {
            case Deposit:
                viewHolder.depositButton.setBackgroundColor(backgroundSelected);
                getDepositButtonIcon().setTextColor(foregroundSelected);
                break;
            case Withdrawal:
                viewHolder.withdrawalButton.setBackgroundColor(backgroundSelected);
                getWithdrawalButtonIcon().setTextColor(foregroundSelected);
                break;
            case Transfer:
                viewHolder.transferButton.setBackgroundColor(backgroundSelected);
                getTransferButtonIcon().setTextColor(foregroundSelected);
                break;
        }

        // Handle the change.

        onTransactionTypeChanged(transactionType);
    }

    public boolean validateData() {
        boolean isTransfer = transactionEntity.getTransactionType().equals(TransactionTypes.Transfer);
        Core core = new Core(mParent);

        if (isTransfer) {
            if (transactionEntity.getAccountToId() == Constants.NOT_SET) {
                core.alert(R.string.error_toaccount_not_selected);
                return false;
            }
            if (transactionEntity.getAccountToId().equals(transactionEntity.getAccountId())) {
                core.alert(R.string.error_transfer_to_same_account);
                return false;
            }

            // Amount To is required and has to be positive.
            if (this.transactionEntity.getAmountTo().toDouble() <= 0) {
                core.alert(R.string.error_amount_must_be_positive);
                return false;
            }
        }

        // Amount is required and must be positive. Sign is determined by transaction type.
        if (transactionEntity.getAmount().toDouble() <= 0) {
            core.alert(R.string.error_amount_must_be_positive);
            return false;
        }

        // Category is required if tx is not a split or transfer.
        boolean hasCategory = transactionEntity.hasCategory();
        if (!hasCategory && (!isSplitSelected()) && !isTransfer) {
            core.alert(R.string.error_category_not_selected);
            return false;
        }

        // Split records must exist if split is checked.
        if (isSplitSelected() && getSplitTransactions().isEmpty()) {
            core.alert(R.string.error_split_transaction_empty);
            return false;
        }
        // Splits sum must be positive.
        if (!CommonSplitCategoryLogic.validateSumSign(getSplitTransactions())){
            core.alert(R.string.split_amount_negative);
            return false;
        }

        return true;
    }

    /**
     * Remove splits when switching to Transfer.
     */
    public void confirmDeletingCategories() {
        removeAllSplitCategories();
        setSplit(false);
        transactionEntity.setTransactionType(TransactionTypes.Transfer);
        onTransactionTypeChanged(TransactionTypes.Transfer);
    }

    /**
     * When cancelling changing the transaction type to Transfer, revert back to the
     * previous transaction type.
     */
    public void cancelChangingTransactionToTransfer() {
        // Select the previous transaction type.
        changeTransactionTypeTo(previousTransactionType);
    }

    /**
     * After the user accepts, remove any split categories.
     */
    public void removeAllSplitCategories() {
        List<ISplitTransaction> splitTransactions = getSplitTransactions();

        for(int i = 0; i < splitTransactions.size(); i++) {
            ISplitTransaction split = splitTransactions.get(i);
            // How do we get this?
            //if (split == null) continue;

            int id = split.getId();
            ArrayList<ISplitTransaction> deletedSplits = getDeletedSplitCategories();

            if(id == -1) {
                // Remove any newly created splits.
                splitTransactions.remove(i);
                i--;
            } else {
                // Delete any splits already in the database. Avoid adding duplicate records.
                if(!deletedSplits.contains(split)) {
                    deletedSplits.add(split);
                }
            }
        }
    }

    /**
     * Check if there is only one Split Category and transforms the transaction to a non-split
     * transaction, removing the split category record.
     * @return True if there is only one split. Need to update the transaction.
     */
    public boolean convertOneSplitIntoRegularTransaction() {
        if (getSplitTransactions().size() != 1) return false;

        // use the first split category record.
        ISplitTransaction splitTransaction = getSplitTransactions().get(0);

        // reuse the amount & category
        transactionEntity.setAmount(splitTransaction.getAmount());
        displayAmountFrom();

        transactionEntity.setCategoryId(splitTransaction.getCategoryId());
        transactionEntity.setSubcategoryId(splitTransaction.getSubcategoryId());
        loadCategoryName();
//        displayCategoryName();

        // reset split indicator & display category
        setSplit(false);

        getDeletedSplitCategories().add(splitTransaction);
        getSplitTransactions().remove(splitTransaction);

        // e deletion in the specific implementation.
        return true;
    }

    // Private

    private void addMissingAccountToSelectors(AccountRepository accountRepository, Integer accountId) {
        if (accountId == null || accountId <= 0) return;

        // #316. In case the account from recurring transaction is not in the visible list,
        // load it separately.
        if (!mAccountIdList.contains(accountId)) {
            Account savedAccount = accountRepository.load(accountId);

            if (savedAccount != null) {
                this.AccountList.add(savedAccount);
                mAccountNameList.add(savedAccount.getName());
                mAccountIdList.add(savedAccount.getId());
            }
        }
    }

    private boolean areCurrenciesSame() {
        if (transactionEntity.getAccountId() == null) return false;
        if (transactionEntity.getAccountToId() == null) return false;

        AccountRepository repo = new AccountRepository(getContext());
        Account accountFrom = repo.load(transactionEntity.getAccountId());
        if (accountFrom == null) return false;

        Account accountTo = repo.load(transactionEntity.getAccountToId());
        if (accountTo == null) return false;

        return accountFrom.getCurrencyId().equals(accountTo.getCurrencyId());
    }

    /**
     * Perform currency exchange to get the Amount From.
     */
    private Money calculateAmountFrom() {
        CurrencyService currencyService = new CurrencyService(getContext());

        return currencyService.doCurrencyExchange(getSourceCurrencyId(), transactionEntity.getAmountTo(),
                getDestinationCurrencyId());
    }

    private Money calculateAmountTo() {
        CurrencyService currencyService = new CurrencyService(getContext());

        return currencyService.doCurrencyExchange(getDestinationCurrencyId(), transactionEntity.getAmount(),
                getSourceCurrencyId());
    }

    private void cancelActivity() {
        mParent.setResult(Activity.RESULT_CANCELED);
        mParent.finish();
    }

    /**
     * Create a split item using the amount and category from the existing transaction.
     * if there is a Category selected, and we are enabling Splits, use the selected category for
     * the initial split record.
     */
    private ISplitTransaction createSplitFromTransaction() {
        // Add the new split record of the same type as the parent.
        ISplitTransaction entity = SplitItemFactory.create(this.mSplitCategoryEntityName, transactionEntity.getTransactionType());

        entity.setAmount(this.transactionEntity.getAmount());

        if (this.transactionEntity.hasCategory()) {
            entity.setCategoryId(this.transactionEntity.getCategoryId());
            entity.setSubcategoryId(this.transactionEntity.getSubcategoryId());
        }

        return entity;
    }

    private void displayAmountFrom() {
        Money amount = transactionEntity.getAmount() == null
            ? MoneyFactory.fromDouble(0)
            : transactionEntity.getAmount();

        displayAmountFormatted(viewHolder.txtAmount, amount, getSourceCurrencyId());
    }

    private void displayAmountTo() {
        // if the currencies are the same, show only one Amount field.
        if (areCurrenciesSame()) {
            viewHolder.tableRowAmountTo.setVisibility(View.GONE);
        } else {
            viewHolder.tableRowAmountTo.setVisibility(View.VISIBLE);
        }

        Money amount = transactionEntity.getAmountTo() == null ? MoneyFactory.fromDouble(0) : transactionEntity.getAmountTo();
        //displayAmountTo(amount);

        displayAmountFormatted(viewHolder.txtAmountTo, amount, getDestinationCurrencyId());
    }

    private void displayAmountFormatted(TextView view, Money amount, Integer currencyId) {
        if (amount == null) return;
        if (currencyId == null || currencyId == Constants.NOT_SET) return;

        CurrencyService currencyService = new CurrencyService(getContext());

        String amountDisplay = currencyService.getCurrencyFormatted(currencyId, amount);

        view.setText(amountDisplay);
        view.setTag(amount.toString());
    }

    private ArrayList<ISplitTransaction> getSplitTransactions() {
        if (mSplitTransactions == null) {
            mSplitTransactions = new ArrayList<>();
        }
        return mSplitTransactions;
    }

    /**
     * Returning from the Split Categories form after OK button was pressed.
     */
    private void onSplitConfirmed(List<ISplitTransaction> splits) {
        if (splits.isEmpty()) {
            // All split categories removed.
            resetCategory();
            setSplit(false);
            return;
        }

        // if there is only one split item, e it immediately.
        if (splits.size() == 1) {
            convertOneSplitIntoRegularTransaction();
            return;
        }

        // Multiple split categories exist at this point.

        resetCategory();

        // indicate that the split is active & refresh display
        setSplit(true);

        // Use the sum of all splits as the Amount.
        Money splitSum = MoneyFactory.fromString("0");
        for (int i = 0; i < splits.size(); i++) {
            splitSum = splitSum.add(splits.get(i).getAmount());
        }
        transactionEntity.setAmount(splitSum);
        displayAmountFrom();
    }

    /**
     * The user is switching to Transfer transaction type.
     */
    private void onTransferSelected() {
        // Check whether to delete split categories, if any.
        if(hasSplitCategories()) {
            // Prompt the user to confirm deleting split categories.
            // Use DialogFragment in order to redraw the binaryDialog when switching device orientation.

            DialogFragment dialog = new YesNoDialog();
            Bundle args = new Bundle();
            args.putString("title", mParent.getString(R.string.warning));
            args.putString("message", mParent.getString(R.string.no_transfer_splits));
            args.putString("purpose", YesNoDialog.PURPOSE_DELETE_SPLITS_WHEN_SWITCHING_TO_TRANSFER);
            dialog.setArguments(args);

            dialog.show(mParent.getSupportFragmentManager(), "tag");

            // Dialog result is handled in onEvent handlers in the listeners.

            return;
        }

        // un-check split.
        setSplit(false);

        // Set the destination account, if not already.
        if (transactionEntity.getAccountToId() == null || transactionEntity.getAccountToId().equals(Constants.NOT_SET)) {
            if (mAccountIdList.size() == 0) {
                // notify the user and exit.
                new MaterialDialog.Builder(getContext())
                        .title(R.string.warning)
                        .content(R.string.no_accounts_available_for_selection)
                        .positiveText(android.R.string.ok)
                        .show();
                return;
            } else {
                transactionEntity.setAccountToId(mAccountIdList.get(0));
            }
        }

        // calculate AmountTo only if not set previously.
        if (transactionEntity.getAmountTo().isZero()) {
            Money amountTo = calculateAmountTo();
            transactionEntity.setAmountTo(amountTo);
        }
        displayAmountTo();
    }

    private void resetCategory() {
        // Reset the Sub/Category on the transaction.
        transactionEntity.setCategoryId(Constants.NOT_SET);
        transactionEntity.setSubcategoryId(Constants.NOT_SET);
    }

    private void showDate(DateTime dateTime) {
        viewHolder.dateTextView.setText(dateTime.toString(Constants.LONG_DATE_PATTERN));
    }

    private void showSplitCategoriesForm(String datasetName) {
        // If there are no splits, use the current values for the initial split record.
        List<ISplitTransaction> splitsToShow = getSplitTransactions();
        if (getSplitTransactions().isEmpty()) {
            ISplitTransaction currentTransaction = createSplitFromTransaction();
            splitsToShow.add(currentTransaction);
        }

        Intent intent = new Intent(getContext(), SplitCategoriesActivity.class);
        intent.putExtra(SplitCategoriesActivity.KEY_DATASET_TYPE, datasetName);
        intent.putExtra(SplitCategoriesActivity.KEY_TRANSACTION_TYPE, transactionEntity.getTransactionType().getCode());
        intent.putExtra(SplitCategoriesActivity.KEY_SPLIT_TRANSACTION, Parcels.wrap(splitsToShow));
        intent.putExtra(SplitCategoriesActivity.KEY_SPLIT_TRANSACTION_DELETED, Parcels.wrap(mSplitTransactionsDeleted));

        Integer fromCurrencyId = getSourceCurrencyId();
        intent.putExtra(SplitCategoriesActivity.KEY_CURRENCY_ID, fromCurrencyId);

        mParent.startActivityForResult(intent, REQUEST_PICK_SPLIT_TRANSACTION);
    }

    /**
     * If the user wants to reset the Split but there are multiple records, show the notice
     * that the records must be adjusted manually.
     */
    private void showSplitResetNotice() {
        new MaterialDialog.Builder(mParent)
                .title(R.string.split_transaction)
                .content(R.string.split_reset_notice)
                .positiveText(android.R.string.ok)
                .show();
    }

    private void setDate(DateTime dateTime) {
        setDirty(true);

        transactionEntity.setDate(dateTime);

        showDate(dateTime);
    }

    private void updateSplitButton() {
        // update Split button
        int buttonColour, buttonBackground;
        if (isSplitSelected()) {
            buttonColour = R.color.button_foreground_active;
            buttonBackground = R.color.button_background_active;
            // #188: if there is a Category selected and we are switching to Split Categories.
        } else {
            buttonColour = R.color.button_foreground_inactive;
            Core core = new Core(getContext());
            buttonBackground = core.usingDarkTheme()
                    ? R.color.button_background_inactive_dark
                    : R.color.button_background_inactive_light;
        }
        viewHolder.splitButton.setTextColor(getContext().getResources().getColor(buttonColour));
        viewHolder.splitButton.setBackgroundColor(getContext().getResources().getColor(buttonBackground));
    }
}
