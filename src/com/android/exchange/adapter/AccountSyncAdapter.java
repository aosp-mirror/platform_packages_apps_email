package com.android.exchange.adapter;

import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.EasSyncService;

import java.io.IOException;
import java.io.InputStream;

public class AccountSyncAdapter extends AbstractSyncAdapter {

    public AccountSyncAdapter(Mailbox mailbox, EasSyncService service) {
        super(mailbox, service);
     }

    @Override
    public void cleanup() {
    }

    @Override
    public String getCollectionName() {
        return null;
    }

    @Override
    public boolean parse(InputStream is) throws IOException {
        return false;
    }

    @Override
    public boolean sendLocalChanges(Serializer s) throws IOException {
        return false;
    }

    @Override
    public boolean isSyncable() {
        return true;
    }
}
