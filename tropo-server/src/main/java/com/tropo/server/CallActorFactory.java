package com.tropo.server;

import com.voxeo.moho.Call;

public interface CallActorFactory {

    public CallActor<?> create(Call call);

}
