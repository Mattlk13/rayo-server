package com.rayo.server;

import com.voxeo.moho.Mixer;

public interface MixerActorFactory {

    public MixerActor create(Mixer mixer, String mixerName);

}
