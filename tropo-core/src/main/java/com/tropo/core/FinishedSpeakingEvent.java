package com.tropo.core;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

public class FinishedSpeakingEvent extends AbstractCallEvent {

    public FinishedSpeakingEvent(String callId) {
        super(callId);
    }


    @Override
    public String toString() {

    	return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)    		
    		.append("callId", getCallId())
    		.toString();
    }
}
