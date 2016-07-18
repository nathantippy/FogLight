package com.ociweb.iot.hardware;

import com.ociweb.pronghorn.iot.schema.GroveResponseSchema;
import com.ociweb.pronghorn.pipe.Pipe;

public interface IODevice {

    
     public boolean isInput();
     public boolean isOutput();
     public boolean isPWM();
     public int     pwmRange();
     public byte[] getReadMessage();
     public boolean isGrove();
     
}
