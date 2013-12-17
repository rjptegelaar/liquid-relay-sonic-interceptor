/**
 * Licensed to the Apache Software Foundation (ASF) under one or more

 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pte.liquid.relay.sonic.converter;

import java.util.Date;
import java.util.Iterator;
import java.util.logging.Logger;

import javax.jms.Destination;

import com.pte.liquid.relay.Converter;
import com.pte.liquid.relay.exception.RelayException;
import com.pte.liquid.relay.model.Message;
import com.sonicsw.xq.XQMessage;
import com.sonicsw.xq.XQMessageException;
import com.sonicsw.xq.XQPart;

public class SonicInterceptorMessageConverterImpl implements Converter<XQMessage> {

	public Message convert(XQMessage xqMsg) throws RelayException {		
		try {
			return convertXQMessage(xqMsg);
		} catch (XQMessageException e) {
			throw new RelayException(e);
		}		
	}
	
	private Message convertXQMessage(XQMessage xqMsg) throws XQMessageException{		
		Message newMsg = new Message();
		
		for (int i=0; i< xqMsg.getPartCount(); i++) {
            XQPart xqPart = xqMsg.getPart(i);
            String label = xqPart.getContentId();
            String content = xqPart.getContent().toString();
            
            if(label==null || "".equals(label))
            	label = "PART_" + i;
            
            newMsg.createPart(label, content);     
            newMsg.setSnapshotTime(new Date());
            
        }
		
		Iterator<String> xqMsgHeaders = xqMsg.getHeaderNames();
		if(xqMsgHeaders!=null){
	    	while (xqMsgHeaders.hasNext()) {
	    		String xqMsgHeader = xqMsgHeaders.next();	    		
	    		if(xqMsgHeader!=null){
	    			
	    			Object headerValue = xqMsg.getHeaderValue(xqMsgHeader);
	    			if((headerValue instanceof Destination)){	    					    				
	    				newMsg.setHeader(xqMsgHeader, headerValue.toString());	    					    				
	    			}else{
	    				newMsg.setHeader(xqMsgHeader, xqMsg.getStringHeader(xqMsgHeader));
	    			}
	    			
	    			
	    		}
	    	}			
		}
		
		return newMsg;				
	}

}
