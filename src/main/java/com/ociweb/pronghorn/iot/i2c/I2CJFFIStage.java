package com.ociweb.pronghorn.iot.i2c;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ociweb.iot.hardware.HardwareImpl;
import com.ociweb.iot.hardware.I2CConnection;
import com.ociweb.pronghorn.iot.AbstractTrafficOrderedStage;
import com.ociweb.pronghorn.iot.schema.I2CCommandSchema;
import com.ociweb.pronghorn.iot.schema.I2CResponseSchema;
import com.ociweb.pronghorn.iot.schema.TrafficAckSchema;
import com.ociweb.pronghorn.iot.schema.TrafficReleaseSchema;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.PipeReader;
import com.ociweb.pronghorn.pipe.PipeWriter;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;
import com.ociweb.pronghorn.util.Appendables;
import com.ociweb.pronghorn.util.math.ScriptedSchedule;

public class I2CJFFIStage extends AbstractTrafficOrderedStage {

	private final I2CBacking i2c;
	private final Pipe<I2CCommandSchema>[] fromCommandChannels;
	private final Pipe<I2CResponseSchema> i2cResponsePipe;

	private static final Logger logger = LoggerFactory.getLogger(I2CJFFIStage.class);
	private ScriptedSchedule schedule;

	private I2CConnection[] inputs = null;
	
	private byte[] workingBuffer;

	private int inProgressIdx = 0;
	private int scheduleIdx = 0;
	
	private long blockStartTime = 0;

	private boolean awaitingResponse = false;

	private static final int MAX_ADDR = 127;

    private Number rate;
	private long timeOut = 0;
	private final int writeTime = 5; //it often takes 1 full ms just to contact the linux driver so this value must be a minimum of 3ms.

	//NOTE: on the pi without any RATE value this stage is run every .057 ms, this is how long 1 run takes to complete for the clock., 2 analog sensors.

	public static final AtomicBoolean instanceCreated = new AtomicBoolean(false);

	public I2CJFFIStage(GraphManager graphManager, Pipe<TrafficReleaseSchema>[] goPipe, 
			Pipe<I2CCommandSchema>[] i2cPayloadPipes, 
			Pipe<TrafficAckSchema>[] ackPipe, 
			Pipe<I2CResponseSchema> i2cResponsePipe,
			HardwareImpl hardware) { 
		super(graphManager, hardware, i2cPayloadPipes, goPipe, ackPipe, i2cResponsePipe); 
		
		assert(!instanceCreated.getAndSet(true)) : "Only one i2c manager can be running at a time";
			
		
		
		this.i2c = hardware.i2cBacking;
		this.fromCommandChannels = i2cPayloadPipes;
		this.i2cResponsePipe = i2cResponsePipe;
		
		//force all commands to happen upon publish and release
		this.supportsBatchedPublish = false;
		this.supportsBatchedRelease = false;
		
		this.inputs = hardware.getI2CInputs();
		
		if (this.hardware.hasI2CInputs()) {			
			this.schedule = this.hardware.buildI2CPollSchedule();    		
		} else {
			logger.debug("skipped buildI2CPollSchedule has no i2c inputs" );
		}

		if (null!=this.schedule) {
			assert(0==(this.schedule.commonClock%10)) : "must be divisible by 10";
			GraphManager.addNota(graphManager, GraphManager.SCHEDULE_RATE, (this.schedule.commonClock)/10 , this); 
		}else{
			logger.debug("Schedule is null");
		}
		
		rate = (Number)graphManager.getNota(graphManager, this.stageId,  GraphManager.SCHEDULE_RATE, null);
		
	}

	@Override
	public void startup(){
		super.startup();

		workingBuffer = new byte[2048];
		
		logger.debug("Polling "+this.inputs.length+" i2cInput(s)");

		for (int i = 0; i < inputs.length; i++) {
			timeOut = hardware.currentTimeMillis() + writeTime;
			while(!i2c.write(inputs[i].address, inputs[i].setup, inputs[i].setup.length) && hardware.currentTimeMillis()<timeOut){};
			logger.debug("I2C setup {} complete",inputs[i].address);
		}

		logger.debug("proposed schedule: {} ",schedule);

		blockStartTime = hardware.nanoTime();//critical Pronghorn contract ensure this start is called by the same thread as run
		
		if (!hasListeners()) {
			logger.debug("No listeners are attached to I2C");
		}
	}


	@Override
	public void run() {
	
	    //never run poll if we have nothing to poll, in that case the array will have a single -1 
	    if (hardware.hasI2CInputs() && hasListeners()) {
	        do {
			    long waitTime = blockStartTime - hardware.nanoTime();
	     		if(waitTime>0){
	     			if (null==rate || (waitTime > 2*rate.longValue())) {    				
	     				processReleasedCommands(waitTime);
	     				return; //Enough time has not elapsed to start next block on schedule
	     			} else {
	     				while (hardware.nanoTime()<blockStartTime){
	     					Thread.yield();
	     					if (Thread.interrupted()) {
	     						requestShutdown();
	     						return;
	     					}
	     				}    				
	     			}
	     		}
        
        		do{
        			inProgressIdx = schedule.script[scheduleIdx];
        			
        			if(inProgressIdx != -1) {
        			    
        				if (!PipeWriter.tryWriteFragment(i2cResponsePipe, I2CResponseSchema.MSG_RESPONSE_10)) {
        					//we are going to miss the schedule due to backup in the pipes, this is common when the unit tests run or the user has put in a break point.
        					processReleasedCommands(40);//if this backup runs long term we never release the commands so we must do it now.
        					return;//oops the pipe is full so we can not read, postpone this work until the pipe is cleared.
        				}

                        I2CConnection connection = this.inputs[inProgressIdx];
                        timeOut = hardware.currentTimeMillis() + writeTime;

                        //Write the request to read
                        while(!i2c.write((byte)connection.address, connection.readCmd, connection.readCmd.length) && hardware.currentTimeMillis()<timeOut){}

                        long now = System.nanoTime();
                        long limit = now + this.inputs[inProgressIdx].delayAfterRequestNS;
        				while(System.nanoTime() < limit) { 
        					//do nothing in here, this is very short and we must get off the bus as fast as possible.
        				}
        				workingBuffer[0] = -2;
        				byte[] temp =i2c.read(this.inputs[inProgressIdx].address, workingBuffer, this.inputs[inProgressIdx].readBytes);
 				
        				PipeWriter.writeInt(i2cResponsePipe, I2CResponseSchema.MSG_RESPONSE_10_FIELD_ADDRESS_11, this.inputs[inProgressIdx].address);						
        				PipeWriter.writeLong(i2cResponsePipe, I2CResponseSchema.MSG_RESPONSE_10_FIELD_TIME_13, hardware.currentTimeMillis());
        				PipeWriter.writeInt(i2cResponsePipe, I2CResponseSchema.MSG_RESPONSE_10_FIELD_REGISTER_14, this.inputs[inProgressIdx].register);
						
						PipeWriter.writeBytes(i2cResponsePipe, I2CResponseSchema.MSG_RESPONSE_10_FIELD_BYTEARRAY_12, temp, 0, this.inputs[inProgressIdx].readBytes, Integer.MAX_VALUE);					

						PipeWriter.publishWrites(i2cResponsePipe);	

						
        			} else {
        				if (rate.longValue()>2_000_000) {
        					processReleasedCommands(rate.longValue()/1_000_000);
        				}
        			}
        			//since we exit early if the pipe is full we must not move this forward until now at the bottom of the loop.
        			scheduleIdx = (scheduleIdx+1) % schedule.script.length;
        		}while(inProgressIdx != -1);
        		blockStartTime += schedule.commonClock;
        		
	        } while (true);
	    } else {
	    	
	    	//System.err.println("nothing to poll, should choose a simpler design");
	    	
	        processReleasedCommands(10);
	    }
	}

	private boolean hasListeners() {
		return i2cResponsePipe != null;
	}

    protected void processMessagesForPipe(int a) {
		sendOutgoingCommands(a);

	}

	private void sendOutgoingCommands(int activePipe) {
		
		if(activePipe == -1){
			return; //No active pipe selected yet
		}
		
		Pipe<I2CCommandSchema> pipe = fromCommandChannels[activePipe];

//		logger.info("i2c while: {} {} {} {} {} {}",
//				activePipe,
//				hasReleaseCountRemaining(activePipe), 
//				isChannelUnBlocked(activePipe), 
//				isConnectionUnBlocked(PipeReader.peekInt(pipe, 1)),
//				PipeReader.hasContentToRead(pipe),
//				pipe
//				);
		
		while ( hasReleaseCountRemaining(activePipe) 
				&& isChannelUnBlocked(activePipe)
				&& PipeReader.hasContentToRead(pipe)
				&& isConnectionUnBlocked(PipeReader.peekInt(pipe, 1)) //peek next connection and check that it is not blocking for some time 
				&& PipeReader.tryReadFragment(pipe)){

			int msgIdx = PipeReader.getMsgIdx(pipe);

			switch(msgIdx){
    			case I2CCommandSchema.MSG_COMMAND_7:
    			{
    			    int connection = PipeReader.readInt(pipe, I2CCommandSchema.MSG_COMMAND_7_FIELD_CONNECTOR_11);
    			    assert isConnectionUnBlocked(connection): "expected command to not be blocked";
    			    
    				int addr = PipeReader.readInt(pipe, I2CCommandSchema.MSG_COMMAND_7_FIELD_ADDRESS_12);
    
    				byte[] backing = PipeReader.readBytesBackingArray(pipe, I2CCommandSchema.MSG_COMMAND_7_FIELD_BYTEARRAY_2);
    				int len  = PipeReader.readBytesLength(pipe, I2CCommandSchema.MSG_COMMAND_7_FIELD_BYTEARRAY_2);
    				int pos = PipeReader.readBytesPosition(pipe, I2CCommandSchema.MSG_COMMAND_7_FIELD_BYTEARRAY_2);
    				int mask = PipeReader.readBytesMask(pipe, I2CCommandSchema.MSG_COMMAND_7_FIELD_BYTEARRAY_2);
    
    
    				Pipe.copyBytesFromToRing(backing, pos, mask, workingBuffer, 0, Integer.MAX_VALUE, len);
    
    				try {
    					if (logger.isDebugEnabled()) {
    						logger.debug("{} send command {} {}", activePipe, Appendables.appendArray(new StringBuilder(), '[', backing, pos, mask, ']', len), pipe);
    					}
    				} catch (IOException e) {
    					throw new RuntimeException(e);
    				}
    
    				timeOut = hardware.currentTimeMillis() + writeTime;
    				while(!i2c.write((byte) addr, workingBuffer, len) && hardware.currentTimeMillis()<timeOut){}
    
    				logger.debug("send done");
    
    			}                                      
    			break;
    
    			case I2CCommandSchema.MSG_BLOCKCHANNEL_22:
    			{
    				blockChannelDuration(activePipe,PipeReader.readLong(pipe, I2CCommandSchema.MSG_BLOCKCHANNEL_22_FIELD_DURATIONNANOS_13));            	   
    				logger.debug("CommandChannel blocked for {} millis ",PipeReader.readLong(pipe, I2CCommandSchema.MSG_BLOCKCHANNEL_22_FIELD_DURATIONNANOS_13));
    			}
    			break;
    
    			case I2CCommandSchema.MSG_BLOCKCONNECTION_20:
    			{  
    			    int connection = PipeReader.readInt(pipe, I2CCommandSchema.MSG_BLOCKCONNECTION_20_FIELD_CONNECTOR_11);
    			    assert isConnectionUnBlocked(connection): "expected command to not be blocked";
    			    
    				int addr = PipeReader.readInt(pipe, I2CCommandSchema.MSG_BLOCKCONNECTION_20_FIELD_ADDRESS_12);
    				long duration = PipeReader.readLong(pipe, I2CCommandSchema.MSG_BLOCKCONNECTION_20_FIELD_DURATIONNANOS_13);

    				blockConnectionDuration(connection, duration);
    				logger.debug("I2C addr {} {} blocked for {} nanos  {}", addr, connection, duration, pipe);
    			}   
    			break;
    
    			case I2CCommandSchema.MSG_BLOCKCONNECTIONUNTIL_21:
    			{  
    			    int connection = PipeReader.readInt(pipe, I2CCommandSchema.MSG_BLOCKCONNECTIONUNTIL_21_FIELD_CONNECTOR_11);
    				int addr = PipeReader.readInt(pipe, I2CCommandSchema.MSG_BLOCKCONNECTIONUNTIL_21_FIELD_ADDRESS_12);
    				long time = PipeReader.readLong(pipe, I2CCommandSchema.MSG_BLOCKCONNECTIONUNTIL_21_FIELD_TIMEMS_14);
    				blockConnectionUntil(connection, time);
    				logger.debug("I2C addr {} {} blocked until {} millis {}", addr, connection, time, pipe);
    			}
    
    			break;    
    			case -1 :
    				requestShutdown();      

			}
			PipeReader.releaseReadLock(pipe);

			//only do now after we know its not blocked and was completed
			decReleaseCount(activePipe);

		}

	}



}