package com.ociweb.oe.foglight.api;

import com.ociweb.iot.grove.adc.ADC_Transducer;
import com.ociweb.iot.maker.FogCommandChannel;
import com.ociweb.iot.maker.FogRuntime;
import com.ociweb.iot.maker.I2CListener;

import static com.ociweb.iot.grove.adc.ADC_Constants.ADDR_ADC121;
import static com.ociweb.iot.grove.adc.ADC_Constants.REG_ADDR_RESULT;

public class I2CListenerBehavior implements I2CListener {

    private final ADC_Transducer sensor;
	private final FogCommandChannel channel;
        
	public I2CListenerBehavior(FogRuntime runtime) {
		channel = runtime.newCommandChannel();
		sensor = new ADC_Transducer(channel);

	}

	@Override
	public void i2cEvent(int addr, int register, long time, byte[] backing, int position, int length, int mask) {
		// i2cEvent triggers when there's data from an i2c read of a device
		// addr is the i2c address of the device
		// register is the register address of the data
		// backing is a circular buffer, with size = mask containing bytes read from i2c
		// position is the index of the first byte of the i2c data read event
		// length is the number of bytes of i2c data read event
		if(addr == ADDR_ADC121 && register == REG_ADDR_RESULT){
			short temp = (short)(((backing[(position)&mask]&0x0F) << 8) | (backing[(position+1)&mask]&0xFF));
			System.out.println("The conversion reading is: "+ temp);
			channel.shutdown();
	     }
	}

}
