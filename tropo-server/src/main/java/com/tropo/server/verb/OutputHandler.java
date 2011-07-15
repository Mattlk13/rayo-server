package com.tropo.server.verb;

import javax.validation.ConstraintValidatorContext;

import com.tropo.core.verb.JumpCommand;
import com.tropo.core.verb.MoveCommand;
import com.tropo.core.verb.Output;
import com.tropo.core.verb.OutputCompleteEvent;
import com.tropo.core.verb.PauseCommand;
import com.tropo.core.verb.ResumeCommand;
import com.tropo.core.verb.SayCompleteEvent.Reason;
import com.tropo.core.verb.SpeedCommand;
import com.tropo.core.verb.Ssml;
import com.tropo.core.verb.VerbCommand;
import com.tropo.core.verb.VerbCompleteEvent;
import com.tropo.core.verb.VolumeCommand;
import com.voxeo.moho.Participant;
import com.voxeo.moho.State;
import com.voxeo.moho.media.output.AudibleResource;
import com.voxeo.moho.media.output.OutputCommand;
import com.voxeo.servlet.xmpp.XmppStanzaError;

public class OutputHandler extends AbstractLocalVerbHandler<Output, Participant> {

    private com.voxeo.moho.media.Output output;

    // Verb Lifecycle
    // ================================================================================

    @Override
    public void start() {

        Ssml prompt = model.getPrompt();
        AudibleResource audibleResource = resolveAudio(prompt);
        OutputCommand outcommand = new OutputCommand(audibleResource);
        if (model.isBargein() != null) {
        	outcommand.setBargein(model.isBargein());
        }
        if (model.getCodec() != null) {
        	outcommand.setCodec(Output.toCodecValue(model.getCodec()));
        }
        if (model.getFormat() != null) {
        	outcommand.setFormat(Output.toFileFormat(model.getFormat()));
        }
        if (model.getJumpPlaylistIncrement() != null) {
        	outcommand.setJumpPlaylistIncrement(model.getJumpPlaylistIncrement());
        }
        if (model.getJumpTime() != null) {
        	outcommand.setJumpTime(model.getJumpTime());
        }
        if (model.getOffset() != null) {
        	outcommand.setOffset(model.getOffset());
        }
        if (model.getRepeatTimes() != null) {
        	outcommand.setRepeatTimes(model.getRepeatTimes());
        }
        if (model.getTimeout() != null) {
        	outcommand.setTimeout(model.getTimeout());
        }
        if (model.getVolumeUnit() != null) {
        	outcommand.setVolumeUnit(model.getVolumeUnit());
        }
        if (model.isStartInPauseMode() != null) {
        	outcommand.setStartInPausedMode(model.isStartInPauseMode());
        }
        
        if (prompt.getVoice() != null) {
        	outcommand.setVoiceName(prompt.getVoice());
        }
        
        output = media.output(outcommand);    	
    }

	@Override
    public boolean isStateValid(ConstraintValidatorContext context) {

        if (isOnConference(participant)) {
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
            complete(new OutputCompleteEvent(model, VerbCompleteEvent.Reason.HANGUP));
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
        } else if (command instanceof JumpCommand) {
            jump(((JumpCommand)command));
        } else if (command instanceof MoveCommand) {
            move((MoveCommand)command);
        } else if (command instanceof SpeedCommand) {
            speed((SpeedCommand)command);
        } else if (command instanceof VolumeCommand) {
            volume((VolumeCommand)command);
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

    public void jump(JumpCommand command) {
    	
    	output.jump(command.getPosition());
    }
    
    public void move(MoveCommand command) {
    	
    	output.move(command.isDirection(), command.getTime());
    }
    
    public void speed(SpeedCommand command) {
    	
    	output.speed(command.isUp());
    }
    
    public void volume(VolumeCommand command) {
    	
    	output.volume(command.isUp());
    }
    
    // Moho Events
    // ================================================================================

    @State
    public void onSpeakComplete(com.voxeo.moho.event.OutputCompleteEvent event) {
        switch(event.getCause()) {
        case BARGEIN:
        case END:
            complete(new OutputCompleteEvent(model, Reason.SUCCESS));
            break;
        case DISCONNECT:
            complete(new OutputCompleteEvent(model, VerbCompleteEvent.Reason.HANGUP));
            break;
        case CANCEL:
            complete(new OutputCompleteEvent(model, VerbCompleteEvent.Reason.STOP));
            break;
        case ERROR:
        case UNKNOWN:
        case TIMEOUT:
            complete(new OutputCompleteEvent(model, VerbCompleteEvent.Reason.ERROR));
            break;
        }
    }

}