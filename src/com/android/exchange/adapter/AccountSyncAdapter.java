package com.android.exchange.adapter;

import com.android.exchange.EasSyncService;

import java.io.IOException;
import java.io.InputStream;

public class AccountSyncAdapter extends AbstractSyncAdapter {

    public AccountSyncAdapter(EasSyncService service) {
        super(service);
     }

    @Override
    public void cleanup() {
    }

    @Override
    public void wipe() {
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

    @Override
    public void sendSyncOptions(Double protocolVersion, Serializer s) throws IOException {
    }
}
