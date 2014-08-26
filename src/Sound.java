import java.io.*;
import java.util.concurrent.PriorityBlockingQueue;
import javax.sound.sampled.*;



public abstract class Sound{
	private PriorityBlockingQueue<SyncData> bufferSyncs,realSyncs;
	private Monitor monitor;
	private AudioFormat fmt;
	private float sampleRate;
	private volatile boolean playing,linear,syncsObserved;
	private volatile double currentVol,targetVol;
	private volatile int untilTarget,bufferPos;
	private volatile long samplesBuffered;
	private SourceDataLine line;
	private Object linelock;
	
	//you may either specify a Monitor to be sent the sample frames as they are buffered, or have monitor be null
	//for example, perhaps you want the sound shown graphically in an oscilloscope or level monitor
	//the monitor will be sent null when playback stops
	public Sound(Monitor monitor){
		this.monitor=monitor;
		fmt=null;
		playing=false;
		currentVol=targetVol=1;
		samplesBuffered=untilTarget=bufferPos=0;
		bufferSyncs=new PriorityBlockingQueue<SyncData>();
		realSyncs=new PriorityBlockingQueue<SyncData>();
		line=null;
		linelock=new Object();
	}
	
	//this method must be called exactly once before play is called
	protected final synchronized void setSampleRate(float sampleRate){
		this.sampleRate=sampleRate;
		if(fmt!=null) throw new IllegalStateException("Sample rate has already been set");
		fmt=new AudioFormat(sampleRate,16,2,true,false);
	}
	
	//move from the current volume to the specified volume, over the course of duration seconds
	//the default volume is 1 (much larger than this will likely cause clipping)
	//if duration is zero or negative, the change will be instantaneous
	//if exponential is true and duration is positive,
	//then the destination volume will never be reached, only asymptotically approached
	//the slope of the volume curve will start off the same as though it were linear
	//this method may not be called before setSampleRate
	//however, it may be called before play, if you want playback to start at a volume other than one
	public final void setVolume(double volume,double duration,boolean exponential){
		if(fmt==null) throw new IllegalStateException("Sample rate has not been set");
		linear=!exponential;
		untilTarget=(int)(duration*sampleRate+.5);
		targetVol=volume;
	}
	
	public final boolean isPlaying(){return playing;}
	
	//use this method to synchronize audio playback with other events
	//causes a sync event to be triggered at a certain point in playback
	//if relative is false, this point will be offset sample frames after playback started
	//if relative is true, this point will be offset sample frames after the current playback position
	//if this point is earlier than the current playback position, the sync event is triggered immediately
	//if realtime is false, the timing is based on when sample frames are buffered
	//if realtime is true, the timing is based on when sample frames are played
	public final void addSync(SoundSync sync,long offset,boolean relative,boolean realtime){
		if(relative){
			if(realtime){
				synchronized(linelock){if(line!=null) offset+=line.getLongFramePosition();}
			}else{
				offset+=samplesBuffered;
			}
		}
		(realtime? realSyncs:bufferSyncs).add(new SyncData(sync,offset));
		syncsObserved=false;
	}
	
	//this is the method that all subclasses of this class must implement
	//each time it is called, it should return a sample frame and move to the next sample frame
	//if it returns null, playback is stopped
	//this is equivalent to calling the stop method
	public abstract SampleFrame getSampleFrame();
	
	//begin playback with a buffer of bufferLen sample frames
	//this method does nothing if the sound is already playing
	public final synchronized void play(int bufferLen) throws LineUnavailableException{
		if(playing) return;
		if(fmt==null) throw new IllegalStateException("Sample rate has not been set");
		new Thread(){
			private int bufferLen;
			private long nextBufferSync,nextRealSync;
			
			public void start(int bufferLen) throws LineUnavailableException{
				this.bufferLen=bufferLen;
				line=(SourceDataLine)AudioSystem.getLine(new DataLine.Info(SourceDataLine.class,fmt));
				line.open(fmt);
				playing=true;
				syncsObserved=false;
				start();
			}
			
			public void run(){
				byte[] buffer=new byte[(bufferLen/2)*4];
				line.start();
				while(playing){
					if(!syncsObserved){
						syncsObserved=true;
						nextBufferSync=nextRealSync=Long.MAX_VALUE;
						SyncData bs=bufferSyncs.peek();
						SyncData rs=realSyncs.peek();
						if(bs!=null) nextBufferSync=bs.time;
						if(rs!=null) nextRealSync=rs.time;
					}
					
					if(line.getLongFramePosition()>=nextRealSync){
						realSyncs.remove().sync.sync();
						syncsObserved=false;
					}
					
					if(samplesBuffered>=nextBufferSync){
						bufferSyncs.remove().sync.sync();
						syncsObserved=false;
					}
					
					if(untilTarget>0){
						currentVol+=(targetVol-currentVol)/untilTarget;
						if(linear) untilTarget--;
					}else currentVol=targetVol;
					
					SampleFrame s=getSampleFrame();
					if(s==null) playing=false;
					else{
						samplesBuffered++;
						buffer(buffer,bufferPos*4,s.left=(int)(s.left*currentVol));
						buffer(buffer,bufferPos*4+2,s.right=(int)(s.right*currentVol));
						bufferPos++;
						if(bufferPos==bufferLen/2){
							line.write(buffer,0,4*bufferPos);
							bufferPos=0;
						}
						synchronized(this){
							while(line.getLongFramePosition()+bufferLen<samplesBuffered){
								try{wait(0,10000);}catch(InterruptedException e){}
							}
						}
						if(monitor!=null) monitor.monitor(s);
					}
				}
				if(monitor!=null) monitor.monitor(null);
				synchronized(linelock){line.close();line=null;}
				samplesBuffered=0;
				bufferPos=0;
				bufferSyncs.clear();
				realSyncs.clear();
			}
			
			private void buffer(byte[] buffer,int index,int value){
				if(value<-32768) value=-32768;
				if(value>=32768) value=32767;
				if(value<0) value+=65536;
				buffer[index]=(byte)(value%256);
				buffer[index+1]=(byte)(value/256);
			}
		}.start(bufferLen);
	}
	
	//stops playback
	//note that this resets the internal playback counter to zero and removes all pending sync events
	//is does not, however, do anything to notify the subclass that playback has stopped
	//meaning that any subsequent calls to getSampleFrame will continue right where they left off
	//in this way, it functions more as a "pause" than a "stop"
	//however, since it resets the internal playback counter, and since sync events are based on this counter,
	//it does function as more as a "stop" with regards to sync events
	public final void stop(){playing=false;}
	
	private class SyncData implements Comparable<SyncData>{
		public SoundSync sync;
		public long time;
		
		public SyncData(SoundSync sync,long time){this.sync=sync;this.time=time;}
		
		@Override
		public boolean equals(Object arg0){
			if(!(arg0 instanceof SyncData)) return false;
			return time==((SyncData)arg0).time;
		}
		
		@Override
		public int compareTo(SyncData arg0){
			if(time>arg0.time) return 1;
			if(time<arg0.time) return -1;
			return 0;
		}
	}
}



//represents a single sample frame of audio data
//left and right values should be from -32768 to 32767, inclusive
//if they are not, clipping occurs
class SampleFrame{public int left=0,right=0;}



//monitors audio data as it's being played
//see constructor of Sound
interface Monitor{public void monitor(SampleFrame s);}



//handles sync events
//see Sound.addSync method
interface SoundSync{public void sync();}



//note that both of the above interfaces have their methods called in the speed-critical audio buffering thread
//it is therefore unwise to put large amounts of code in an implementation of Monitor.monitor or SoundSync.sync
//if large amounts of computation are required by either method,
//it would be better for said method to cause the computation to happen in another thread



//a Sound which plays a *.wav file
class WavSound extends Sound{
	private int pos;
	private boolean loop;
	private int[][] samples;
	
	//if loop is true, getSampleFrame will never return null
	//if loop is false, getSampleFrame will return null when the end of the file is reached
	//note that the constructor may take a long time to execute for large wav files
	//on my computer, it loads at about 200 kilobytes/second
	//I tried solving this by having a "loader thread" which loads the file in the background,
	//so you can play the file as its loading,
	//rather than having to load the entire file into memory before being able to play it,
	//but it turned out to be impractical to make it not hog so much cpu time that the player thread stutters
	//note that this constructor assumes the "fmt " chunk will be the first one in the file
	//the "data" chunk may be anywhere in the file though
	//all other chunks are ignored
	public WavSound(InputStream in,boolean loop,Monitor monitor) throws IOException{
		super(monitor);
		this.loop=loop;
		pos=0;
		readInt(in,16);
		int formatSize=readInt(in,4);
		readInt(in,2);
		boolean stereo=readInt(in,2)==2;
		setSampleRate(readInt(in,4));
		readInt(in,6);
		boolean hidef=readInt(in,2)==16;
		readInt(in,formatSize-16);

		int[] dataStr={'d','a','t','a'};
		int expected=0,pval=1;
		for(int x:dataStr){expected+=x*pval;pval*=256;}
		int header=readInt(in,4);
		while(header!=expected){int size=readInt(in,4);readInt(in,size);header=readInt(in,4);}
		
		int dataSize=readInt(in,4);
		int sampleSize=stereo? 2:1;
		if(hidef) sampleSize*=2;
		dataSize/=sampleSize;
		samples=new int[dataSize][2];
		for(int i=0;i<dataSize;i++){
			samples[i][0]=readSample(in,hidef);
			if(stereo) samples[i][1]=readSample(in,hidef);
			else samples[i][1]=samples[i][0];
		}
	}
	
	private int readSample(InputStream in,boolean hidef) throws IOException{
		int a=in.read();
		if(a==-1) throw new EOFException();
		if(!hidef) return (a-128)*256;
		int b=in.read();
		if(b==-1) throw new EOFException();
		if(b>=128) b-=256;
		return b*256+a;
	}
	
	private int readInt(InputStream in,int bytes) throws IOException{
		int ans=0,pval=1;
		for(int i=0;i<bytes;i++){
			int b=in.read();
			if(b==-1) throw new EOFException();
			ans+=b*pval;
			pval*=256;
		}
		return ans;
	}
	
	public SampleFrame getSampleFrame(){
		if(pos==samples.length) return null;
		int[] sample=samples[pos++];
		if(pos==samples.length && loop) pos=0;
		SampleFrame ans=new SampleFrame();
		ans.left=sample[0];
		ans.right=sample[1];
		return ans;
	}
}