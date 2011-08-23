package com.tropo.server.verb;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.media.mscontrol.EventType;
import javax.media.mscontrol.Parameters;
import javax.media.mscontrol.join.Joinable.Direction;
import javax.media.mscontrol.mixer.MediaMixer;
import javax.media.mscontrol.mixer.MixerEvent;
import javax.validation.ConstraintValidatorContext;

import com.tropo.core.verb.Conference;
import com.tropo.core.verb.ConferenceCompleteEvent;
import com.tropo.core.verb.ConferenceCompleteEvent.Reason;
import com.tropo.core.verb.KickCommand;
import com.tropo.core.verb.MuteCommand;
import com.tropo.core.verb.OffHoldEvent;
import com.tropo.core.verb.OnHoldEvent;
import com.tropo.core.verb.Ssml;
import com.tropo.core.verb.UnmuteCommand;
import com.tropo.core.verb.VerbCommand;
import com.tropo.core.verb.VerbCompleteEvent;
import com.tropo.server.CallManager;
import com.tropo.server.EventHandler;
import com.tropo.server.MixerActor;
import com.tropo.server.MixerActorFactory;
import com.tropo.server.MixerRegistry;
import com.tropo.server.MohoUtil;
import com.tropo.server.conference.ParticipantController;
import com.tropo.server.exception.ExceptionMapper;
import com.voxeo.moho.Call;
import com.voxeo.moho.Participant;
import com.voxeo.moho.Participant.JoinType;
import com.voxeo.moho.State;
import com.voxeo.moho.conference.ConferenceController;
import com.voxeo.moho.event.InputCompleteEvent;
import com.voxeo.moho.media.Input;
import com.voxeo.moho.media.Output;
import com.voxeo.moho.media.input.DigitInputCommand;
import com.voxeo.moho.media.output.OutputCommand;
import com.voxeo.moho.media.output.OutputCommand.BehaviorIfBusy;
import com.voxeo.moho.spi.ExecutionContext;
import com.voxeo.servlet.xmpp.StanzaError;

public class ConferenceHandler extends AbstractLocalVerbHandler<Conference, Call> implements ParticipantController {

    public static final String PARTICIPANT_KEY = "com.tropo.conference.participant.";
    private static final String WAIT_LIST_KEY = "com.tropo.conference.waitList";

    private ConferenceController mohoConferenceController;
    private MixerActorFactory mixerActoryFactory;
    private MixerRegistry mixerRegistry;
    private CallManager callManager;

    private boolean hold;
    private boolean joined;
    private Output<Participant> holdMusic;
    private Input<Participant> hotwordListener;
    private com.voxeo.moho.conference.Conference mohoConference;

    // Represent the current state of the participant
    private boolean mute;
    private Character terminator;
    private boolean tonePassthrough;

    @Override
    public void start() {

        // Init local state from verb request
        this.mute = model.isMute();
        this.terminator = model.getTerminator();
        this.tonePassthrough = model.isTonePassthrough();

        // Create or get Moho Conference
        ExecutionContext ctx = (ExecutionContext)participant.getApplicationContext();
        Parameters parameters = ctx.getMSFactory().createParameters();
        parameters.put(MediaMixer.ENABLED_EVENTS, new EventType[]{MixerEvent.ACTIVE_INPUTS_CHANGED});
        mohoConference = participant.getApplicationContext().getConferenceManager()
        	.createConference(model.getRoomName(),Integer.MAX_VALUE,parameters);
        
        synchronized (mohoConference.getId()) {

            // If this is the first participant then we should configure the controller
            if (mohoConference.getController() == null) {
                mohoConference.setController(mohoConferenceController);
            }
            
            // Register Tropo Participant object as an attribute of the Moho Call
            participant.setAttribute(getMohoParticipantAttributeKey(), this);

            // Determine if the conference is 'open' by checking if there are any moderators
            Boolean open = isConferenceOpen();
            
            // If the conference was not open and we're a moderator then open it up
            if (!open && model.isModerator()) {
                // Join all waiting participant to the mixer
                for (com.voxeo.moho.Participant mixerParticipant : getWaitList()) {
                    ParticipantController controller = (ParticipantController) mixerParticipant.getAttribute(getMohoParticipantAttributeKey());
                    controller.setHold(false);
                }
                open = true;
            }

            // Join the conference if it's open or play music if it's not
            if (open) {
                setHold(false);
                playAnnouncement();
            }
            else {
                setHold(true);
            }
        }

        //TODO: This is the only place I found to create the actual conference actor. I didn't find events for 
        // conference/mixer creation
        MixerActor actor = mixerActoryFactory.create(mohoConference);
        actor.setupMohoListeners(mohoConference);
        // Wire up default call handlers
        for (EventHandler handler : callManager.getEventHandlers()) {
            actor.addEventHandler(handler);
        }
        actor.start();
        mixerRegistry.add(actor);
                
    }

    @Override
    public boolean isStateValid(ConstraintValidatorContext context) {

        String participantKey = getMohoParticipantAttributeKey();
        if (participant.getAttribute(participantKey) != null) {
        	context.buildConstraintViolationWithTemplate(
        			"Call is already a member of the conference: " + model.getRoomName())
        			.addNode(ExceptionMapper.toString(StanzaError.Condition.RESOURCE_CONSTRAINT))
        			.addConstraintViolation();
        	return false;
        }
        if (isOnConference(participant)) {
        	context.buildConstraintViolationWithTemplate(
        			"Call is already joined to another conference")
        			.addNode(ExceptionMapper.toString(StanzaError.Condition.RESOURCE_CONSTRAINT))
        			.addConstraintViolation();
        	return false;
        }
        return true;
    }

	public void stop(boolean hangup) {
        try {
            preStop(hangup);
            complete(new ConferenceCompleteEvent(model, hangup ? VerbCompleteEvent.Reason.HANGUP : VerbCompleteEvent.Reason.STOP));
        }
        catch (RuntimeException e) {
            complete(new ConferenceCompleteEvent(model, e.getMessage()));
            throw e;
        } finally {
        	mixerRegistry.remove(mohoConference.getId());
        }
    }

    @Override
    public void onCommand(VerbCommand command) {
        if (command instanceof KickCommand) {
            kick(((KickCommand)command).getReason());
        }
        else if (command instanceof MuteCommand) {
            setMute(true);
        }
        else if (command instanceof UnmuteCommand) {
            setMute(false);
        }
    }

    // Commands
    // ================================================================================

    public synchronized boolean isHold() {
        return hold;
    }

    public synchronized void setHold(boolean hold) {

        synchronized (mohoConference.getId()) {
            if (hold) {
                stopMixing();
                startMusic();
                startHotwordListener();
                addCallToWaitList();
                fire(new OnHoldEvent(model));
            }
            else {
                stopMusic();
                startMixing();
                startHotwordListener();
                removeCallFromWaitList();
                fire(new OffHoldEvent(model));
            }
        }

        this.hold = hold;
    }

    private List<Participant> getWaitList() {
        synchronized (mohoConference.getId()) {
            List<Participant> waitList = mohoConference.getAttribute(WAIT_LIST_KEY);
            if(waitList == null) {
                waitList = new CopyOnWriteArrayList<Participant>();
                mohoConference.setAttribute(WAIT_LIST_KEY, waitList);
            }
            return waitList;
        }
    }

    private void addCallToWaitList() {
        synchronized (mohoConference.getId()) {
            getWaitList().add(participant);
        }
    }

    private void removeCallFromWaitList() {
        synchronized (mohoConference.getId()) {
            List<Participant> waitList = mohoConference.getAttribute(WAIT_LIST_KEY);
            if(waitList != null) {
                waitList.remove(participant);
            }
        }
    }

    public synchronized boolean isMute() {
        return mute;
    }

    public synchronized void setMute(boolean mute) {
        this.mute = mute;
        startMixing();
    }

    public synchronized boolean isTonePassthrough() {
        return tonePassthrough;
    }

    public synchronized void setTonePassthrough(boolean tonePassthrough) {
        this.tonePassthrough = tonePassthrough;
        startMixing();
    }

    public synchronized Character getTerminator() {
        return terminator;
    }

    public synchronized void setTerminator(Character terminator) {
        this.terminator = terminator;
        startMixing();
    }

    public synchronized void kick(String reason) {
        complete(Reason.KICK, reason);
    }
    
    public boolean isModerator() {
        return model.isModerator();
    }

    // Util
    // ================================================================================

    /**
     * @param hangup True if we're baing called as the result of a hangup event
     */
    private void preStop(boolean hangup) {
        
        synchronized (mohoConference.getId()) {
            
        	participant.setAttribute(getMohoParticipantAttributeKey(), null);
            removeCallFromWaitList();

            // Free up media resources if the call is still active
            if(!hangup) {
                stopMixing();
                stopMusic();
                stopHotwordListener();
            }
            
            // Check if we're the last moderator and if so put the remaining participants on hold
            if(model.isModerator() && isLastModerator()) {
                // Put remaining participants on hold
                for (com.voxeo.moho.Participant mixerParticipant : mohoConference.getParticipants()) {
                    ParticipantController controller = (ParticipantController) mixerParticipant.getAttribute(getMohoParticipantAttributeKey());
                    controller.setHold(true);
                }
            }
        }
        
    }
    
    private boolean isConferenceOpen() {
        return mohoConference.getParticipants().length > 0;
    }    

    private boolean isLastModerator() {
        synchronized (mohoConference.getId()) {
            for (com.voxeo.moho.Participant mixerParticipant : mohoConference.getParticipants()) {
                ParticipantController controller = (ParticipantController) mixerParticipant.getAttribute(getMohoParticipantAttributeKey());
                if(controller.isModerator()) {
                    return false;
                }
            }
            return true;
        }
    }    

    private String getMohoParticipantAttributeKey() {
        return (PARTICIPANT_KEY + model.getRoomName());
    }

    private void startMixing() {
        if (!joined) {
            Properties props = new Properties();
            props.setProperty("playTones", Boolean.toString(tonePassthrough));
            // TODO: Added property for 'beep' when it becomes available
            // https://evolution.voxeo.com/ticket/1430585
            try {
                mohoConference.join(participant, JoinType.BRIDGE, mute ? Direction.RECV : Direction.DUPLEX, props).get();
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
            joined = true;
        }
    }

    private void stopMixing() {
        if (joined) {
        	participant.unjoin(mohoConference);
            joined = false;
        }
    }

    private void startHotwordListener() {
        if (hotwordListener == null || hotwordListener.isDone()) {
            // Re-enable terminator because recognition stops after we join the mixer
            if (terminator != null) {
                DigitInputCommand inputCommand = new DigitInputCommand(terminator);
                inputCommand.setDtmfHotword(true);
                hotwordListener = getMediaService().input(inputCommand);
            }
        }
    }

    private void stopHotwordListener() {
        if (hotwordListener != null && !hotwordListener.isDone()) {
            hotwordListener.stop();
        }
    }

    private void startMusic() {
        Ssml holdMusicPrompt = model.getHoldMusic();
        if (holdMusicPrompt != null) {
            OutputCommand outputCommand = MohoUtil.output(holdMusicPrompt);
            outputCommand.setRepeatTimes(1000);
            holdMusic = getMediaService().output(outputCommand);
        }
    }

    private void playAnnouncement() {
        Ssml announcementPrompt = model.getAnnouncement();
        if (announcementPrompt != null) {
            OutputCommand outputCommand = MohoUtil.output(announcementPrompt);
            outputCommand.setBahavior(BehaviorIfBusy.QUEUE);
            mohoConference.output(outputCommand);
        }
    }

    private void stopMusic() {
        if (holdMusic != null && !holdMusic.isDone()) {
            holdMusic.stop();
        }
    }

    private void complete(Reason reason) {
        complete(reason, null);
    }

    private void complete(Reason reason, String kickReason) {
        try {
            preStop(false);
            ConferenceCompleteEvent event = new ConferenceCompleteEvent(model, reason);
            event.setKickReason(kickReason);
            complete(event);
        }
        catch (RuntimeException e) {
            complete(new ConferenceCompleteEvent(model, e.getMessage()));
            throw e;
        }
    }

    // Config
    // ================================================================================
    
    public ConferenceController getMohoConferenceController() {
        return mohoConferenceController;
    }
    
    public void setMohoConferenceController(ConferenceController mohoConferenceController) {
        this.mohoConferenceController = mohoConferenceController;
    }

    // Moho Events
    // ================================================================================

    @State
    public void onTermChar(InputCompleteEvent<Participant> event) {
        // This event can come from another verb so we first check that
        if(hotwordListener != null && event.hasMatch() && event.getSource() == participant) {
            complete(Reason.TERMINATOR);
        }
    }
    
    public void setMixerActoryFactory(MixerActorFactory mixerActoryFactory) {
		
    	this.mixerActoryFactory = mixerActoryFactory;
	}
    
    public void setMixerRegistry(MixerRegistry mixerRegistry) {
	
    	this.mixerRegistry = mixerRegistry;
	}

	public void setCallManager(CallManager callManager) {
		this.callManager = callManager;
	}
}
