package com.tropo.server.exception;

import javax.validation.ConstraintViolation;

import com.tropo.core.validation.ValidationException;
import com.voxeo.exceptions.NotFoundException;
import com.voxeo.logging.Loggerf;
import com.voxeo.servlet.xmpp.XmppStanzaError;

public class ExceptionMapper {

	private static final Loggerf log = Loggerf.getLogger(ExceptionMapper.class);
	 
	public ErrorMapping toXmppError(Exception e) {
		
		String errorType = XmppStanzaError.Type_CANCEL;
		String errorCondition = XmppStanzaError.INTERNAL_SERVER_ERROR_CONDITION;
		String errorMessage = e.getMessage();
		
		if (e instanceof ValidationException) {
			ConstraintViolation<?> violation = ((ValidationException)e).getFirstViolation();
			if (violation.getPropertyPath() !=  null) {
				errorCondition = violation.getPropertyPath().toString();
			} else {
				errorCondition = XmppStanzaError.BAD_REQUEST_CONDITION;
			}
			errorType = XmppStanzaError.Type_MODIFY;
			if (violation != null) {				
				if (violation.getMessageTemplate() != null) {
					errorMessage = violation.getMessageTemplate();				
				} else {
					errorMessage = String.format("Invalid value for property %s. Error: %s",violation.getPropertyPath(),violation.getMessage());
				}
			} else {
				if (e.getMessage() != null) {
					errorMessage = e.getMessage();
				}
			}
			
			return new ErrorMapping(errorType, errorCondition, errorMessage);
		}
		else if(e instanceof NotFoundException) {
		    errorCondition = XmppStanzaError.ITEM_NOT_FOUND_CONDITION;
		} else if (e instanceof IllegalArgumentException) {
			errorCondition = XmppStanzaError.BAD_REQUEST_CONDITION;
		}
		
		log.debug("Mapping unknown exception [type=%s, message=%s]",e.getClass(), e.getMessage());
		return new ErrorMapping(errorType, errorCondition, errorMessage);
	}
}
