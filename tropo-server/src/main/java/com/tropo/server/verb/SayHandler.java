package com.tropo.server.verb;

import javax.validation.ConstraintValidatorContext;

import com.tropo.core.verb.PauseCommand;
import com.tropo.core.verb.ResumeCommand;
import com.tropo.core.verb.Say;
import com.tropo.core.verb.SayCompleteEvent;
import com.tropo.core.verb.Ssml;
import com.tropo.core.verb.VerbCompleteEvent;
import com.tropo.core.verb.SayCompleteEvent.Reason;
import com.tropo.core.verb.VerbCommand;
import com.voxeo.moho.State;
import com.voxeo.moho.event.OutputCompleteEvent;
import com.voxeo.moho.media.Output;
import com.voxeo.moho.media.output.AudibleResource;
import com.voxeo.moho.media.output.OutputCommand;
import com.voxeo.servlet.xmpp.XmppStanzaError;

public class SayHandler extends AbstractLocalVerbHandler<Say> {

    private Output output;

    // Verb Lifecycle
    // ================================================================================

    @Override
    public void start() {

        Ssml prompt = model.getPrompt();
        AudibleResource audibleResource = resolveAudio(prompt);
        OutputCommand outcommand = new OutputCommand(audibleResource);
        outcommand.setBargein(false);
        outcommand.setVoiceName(prompt.getVoice());
        
        output = media.output(outcommand);
        
    }

    @Override
    public boolean isStateValid(ConstraintValidatorContext context) {

        if (isOnConference(call)) {
        	context.buildConstraintViolationWithTemplate(
        			"Call is joined to a conference.")
        			.addNode(XmppStanzaError.RESOURCE_CONSTRAINT_CONDITION)
        			.addConstraintViolation();
        	return false;
        }
        return true;
    }
    
    // Commands
    // ================================================================================

    public void stop(boolean hangup) {
        if(hangup) {
            complete(new SayCompleteEvent(model, VerbCompleteEvent.Reason.HANGUP));
        }
        else {
            output.stop();
        }
    }

    @Override
    public void onCommand(VerbCommand command) {
        if (command instanceof PauseCommand) {
            pause();
        } else if (command instanceof ResumeCommand) {
            resume();
        }
    }

    @State
    public void pause() {
        output.pause();
    }

    @State
    public void resume() {
        output.resume();
    }

    // Moho Events
    // ================================================================================

    @State
    public void onSpeakComplete(OutputCompleteEvent event) {
        switch(event.getCause()) {
        case BARGEIN:
        case END:
            complete(new SayCompleteEvent(model, Reason.SUCCESS));
            break;
        case DISCONNECT:
            complete(new SayCompleteEvent(model, VerbCompleteEvent.Reason.HANGUP));
            break;
        case CANCEL:
            complete(new SayCompleteEvent(model, VerbCompleteEvent.Reason.STOP));
            break;
        case ERROR:
        case UNKNOWN:
        case TIMEOUT:
            complete(new SayCompleteEvent(model, VerbCompleteEvent.Reason.ERROR));
            break;
        }
    }

}
