package com.rayo.core.verb;

import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.AssertTrue;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.hibernate.validator.constraints.NotEmpty;
import org.joda.time.Duration;

import com.rayo.core.validation.Messages;
import com.rayo.core.validation.ValidRecognizer;

public class Input extends BaseVerb {

	@Valid
	@NotEmpty(message = Messages.MISSING_CHOICES)
	private List<Choices> grammars;

	@ValidRecognizer
	private String recognizer;

	private Duration initialTimeout;
	private Duration interDigitTimeout;
	private Float minConfidence = 0.3f;
	private Float sensitivity;
	private Character terminator;
	private InputMode mode = InputMode.ANY;

	public Duration getInitialTimeout() {
		return initialTimeout;
	}

	public void setInitialTimeout(Duration initialTimeout) {
		this.initialTimeout = initialTimeout;
	}

	public Duration getInterDigitTimeout() {
		return interDigitTimeout;
	}

	public void setInterDigitTimeout(Duration interSigTimeout) {

		this.interDigitTimeout = interSigTimeout;
	}

	public Float getMinConfidence() {
		return minConfidence;
	}

	public void setMinConfidence(Float confidence) {
		this.minConfidence = confidence;
	}

	public Float getSensitivity() {
		return sensitivity;
	}

	public void setSensitivity(Float sensitivity) {
		this.sensitivity = sensitivity;
	}

	public String getRecognizer() {
		return recognizer;
	}

	public void setRecognizer(String recognizer) {
		this.recognizer = recognizer;
	}

	public Character getTerminator() {
		return terminator;
	}

	public void setTerminator(Character terminator) {
		this.terminator = terminator;
	}

	public InputMode getMode() {
		return mode;
	}

	public void setMode(InputMode inputMode) {
		this.mode = inputMode;
	}

	public List<Choices> getGrammars() {
		return grammars;
	}

	public void setGrammars(List<Choices> grammars) {
		this.grammars = grammars;
	}
	
    @AssertTrue(message=Messages.INVALID_CONFIDENCE_RANGE)
    public boolean isMinConfidenceWithinRange() {
        return (minConfidence >= 0f && minConfidence <= 1f);
    }
    
    @AssertTrue(message=Messages.INVALID_SENSITIVITY_RANGE)
    public boolean isSensitivityWithinRange() {
        return sensitivity == null || (sensitivity >= 0f && sensitivity <= 1f);
    }

	@Override
	public String toString() {

		return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
				.append("callId", getCallId()).append("verbId", getVerbId())
				.append("minConfidence", getMinConfidence())
				.append("initialTimeout", getInitialTimeout())
				.append("mode", getMode())
				.append("integerSigTimeout", getInterDigitTimeout())
				.append("recognizer", getRecognizer())
				.append("sensitivity", getSensitivity())
				.append("terminator", getTerminator())
				.append("grammars", grammars).toString();
	}

}
