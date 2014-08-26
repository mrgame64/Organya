import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;



public class Main{
	public static JJPanel panel;
	public static BufferedImage buttons;
	
	public static void main(String[] args) throws IOException{
		buttons=ImageIO.read(new File("buttons.png"));
		
		JFrame frame=new JFrame("Organya player");
		panel=new JJPanel();
		frame.setContentPane(panel);
		frame.setVisible(true);
		
		Insets insets=frame.getInsets();
		int minWidth=450+insets.left+insets.right;
		int minHeight=300+insets.top+insets.bottom;
		
		frame.setSize(minWidth,minHeight);
		frame.setMinimumSize(new Dimension(minWidth,minHeight));
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		
		MouseListen m=new MouseListen();
		panel.addMouseListener(m);
		panel.addMouseMotionListener(m);
		frame.addMouseWheelListener(m);
	}
}



class Levels implements Monitor{
	private int delay,timer,lpeak,rpeak,clip;
	
	public Levels(int delay){
		this.delay=delay;
	}
	
	@Override
	public void monitor(SampleFrame s){
		if(s==null) return;
		if(lpeak>0) lpeak--;
		if(rpeak>0) rpeak--;
		if(clip>0) clip--;
		lpeak=Math.max(lpeak,Math.abs(s.left));
		rpeak=Math.max(rpeak,Math.abs(s.right));
		if(lpeak>32767) lpeak=clip=32767;
		if(rpeak>32767) rpeak=clip=32767;
		if(++timer==delay){timer=0;Main.panel.repaint();}
	}
	
	public void reset(){
		timer=lpeak=rpeak=clip=0;
	}
	
	public void paint(Graphics g,int x,int y,int w,int h){
		int h2=h-255;
		g.setColor(new Color(clip/128,0,0));
		g.fillRect(x,y,w,h2);
		y+=h2;
		g.setColor(Color.black);
		g.fillRect(x,y,w,255);
		for(int i=0;i<lpeak/128;i++){
			g.setColor(new Color(i,255,0));
			g.drawLine(x,y+255-i,x+w/2-1,y+255-i);
		}
		for(int i=0;i<rpeak/128;i++){
			g.setColor(new Color(i,255,0));
			g.drawLine(x+w/2,y+255-i,x+w-1,y+255-i);
		}
	}
}



class JJPanel extends JPanel{
	private static final long serialVersionUID=1;
	private File[] files;
	private Organya org;
	private File orgFile;
	private Levels monitor;
	private int dirqty,scrollpos,dragging,dragfrom,oldscrollpos;
	private boolean hasParent,playing;
	private double volume;
	
	public JJPanel(){
		setDir(new File("").getAbsoluteFile());
		playing=false;
		monitor=new Levels(1000);
		volume=1;
	}
	
	private void setDir(File file){
		files=file.listFiles();
		int fileqty=files.length;
		dirqty=0;
		int index=0;
		while(index<fileqty){
			if(files[index].isDirectory() || files[index].getName().endsWith(".org")){
				if(files[index++].isDirectory()) dirqty++;
			}else{
				File temp=files[--fileqty];
				files[fileqty]=files[index];
				files[index]=temp;
			}
		}
		
		File[] temp=new File[fileqty];
		for(int i=0;i<fileqty;i++) temp[i]=files[i];
		Arrays.sort(temp,new Comparator<File>(){
			@Override
			public int compare(File o1,File o2){
				if(o1.isDirectory()^o2.isDirectory()) return o1.isDirectory()? -1:1;
				String n1=o1.getName(),n2=o2.getName();
				if(!o1.isDirectory()){n1=n1.substring(0,n1.length()-4);n2=n2.substring(0,n2.length()-4);}
				return n1.compareToIgnoreCase(n2);
			}
		});
		
		File parent=file.getParentFile();
		index=(hasParent=parent!=null)? 1:0;
		files=new File[index+fileqty];
		for(File f:temp) files[index++]=f;
		if(hasParent){files[0]=parent;dirqty++;}
		repaint();
	}
	
	private void play(){
		if(org==null) return;
		try{org.play(8000);playing=true;repaint();}
		catch(Exception e){e.printStackTrace();}
	}
	
	private void pause(){
		if(org!=null) org.stop();
		playing=false;
	}
	
	private void rewind(){
		if(playing) pause();
		if(org!=null) org.reset();
		monitor.reset();
	}
	
	@Override
	public void paint(Graphics g){
		int w=getWidth(),h=getHeight();
		int maxscroll=20*files.length-h;
		scrollpos=Math.max(0,Math.min(scrollpos,maxscroll));
		
		g.setColor(Color.white);
		g.fillRect(0,0,w,h);
		g.setColor(Color.black);
		g.setFont(new Font("Verdana",Font.BOLD,12));
		for(int i=0;i<files.length;i++){
			int iconX=150,iconY=0;
			boolean isParent=hasParent && i==0;
			if(i<dirqty){if(!isParent) iconX=180;}
			else iconY=20;
			int y=20*i-scrollpos;
			if(files[i].equals(orgFile)){
				g.setColor(Color.yellow);
				g.fillRect(0,y,w-150,20);
				g.setColor(Color.black);
			}
			g.drawImage(Main.buttons,0,y,30,y+20,iconX,iconY,iconX+30,iconY+20,null);
			String name=isParent? "[parent directory]":files[i].getName();
			g.drawString(name.substring(0,name.length()-(i<dirqty? 0:4)),33,y+17);
		}
		
		if(maxscroll>0){
			int bartop=(scrollpos*h)/(20*files.length);
			int barlen=(h*h)/(20*files.length);
			
			g.setColor(Color.gray);
			g.fillRect(w-170,0,20,h);
			g.setColor(Color.blue);
			g.fillRect(w-170,bartop,20,barlen);
			g.setColor(Color.black);
			g.drawLine(w-170,0,w-170,h);
			g.drawLine(w-151,0,w-151,h);
			g.drawLine(w-170,bartop,w-151,bartop);
			g.drawLine(w-170,bartop+barlen,w-151,bartop+barlen);
		}
		
		int x=w-150,iconX=playing? 50:0;
		g.drawImage(Main.buttons,x,0,x+50,40,iconX,0,iconX+50,40,null);
		g.drawImage(Main.buttons,x+50,0,x+100,40,100,0,150,40,null);
		
		g.setColor(Color.white);
		g.fillRect(x,40,100,h-40);
		g.setColor(Color.black);
		g.setFont(new Font("Verdana",Font.PLAIN,10));
		g.drawString("Volume: "+((int)(volume*100+.5))+"%",x+8,h-10);
		g.drawLine(x,40,x,h);
		g.drawLine(x+50,55,x+50,h-45);
		for(int i=0;i<=4;i++){
			int y=55+((h-100)*i)/4;
			g.drawLine(x+49,y,x+50,y);
		}
		int y=55+(int)((h-100)*(2-volume)/2);
		g.drawImage(Main.buttons,x+35,y-9,x+65,y+11,180,20,210,40,null);
		
		monitor.paint(g,x+100,0,50,h);
	}
	
	private void setVolume(int y){
		int top=55,bottom=getHeight()-45;
		volume=bottom-y;
		volume/=bottom-top;
		volume*=2;
		if(volume>2) volume=2;
		if(volume<0) volume=0;
		if(org!=null) org.setVolume(volume,0,false);
		repaint();
	}
	
	public void mouseClick(int x,int y){
		int w=getWidth(),h=getHeight();
		if(x<w-150){
			//file explorer
			if(x>w-170 && files.length*20>h){
				//scrollbar
				dragging=1;
				dragfrom=y;
				oldscrollpos=scrollpos;
			}else{
				int index=(y+scrollpos)/20;
				if(index<dirqty){
					//a directory
					setDir(files[index]);
				}else if(index<files.length){
					//an org file
					orgFile=files[index];
					if(playing) pause();
					try{
						InputStream in=new FileInputStream(files[index]);
						org=new Organya(in,new FileInputStream("orgsamp.dat"),30000,monitor);
						org.setVolume(volume,0,false);
						monitor.reset();
						play();
					}catch(IOException e){e.printStackTrace();}
					repaint();
				}
			}
		}else if(x<w-50){
			if(y<40){
				//buttons
				if(x<w-100){if(playing) pause();else play();}
				else rewind();
				repaint();
			}else{
				//volume control
				setVolume(y);
				dragging=2;
			}
		}
	}
	
	public void mouseDrag(int y){
		int h=getHeight();
		if(dragging==1){
			//scrollbar
			int dy=y-dragfrom;
			scrollpos=oldscrollpos+(dy*20*files.length)/h;
			repaint();
		}
		if(dragging==2){
			//volume
			setVolume(y);
		}
	}
	
	public void mouseRelease(){dragging=0;}
	
	public void scroll(int amount){
		scrollpos+=amount*15;
		repaint();
	}
}



class MouseListen implements MouseListener,MouseMotionListener,MouseWheelListener{
	@Override
	public void mousePressed(MouseEvent arg0){Main.panel.mouseClick(arg0.getX(),arg0.getY());}

	@Override
	public void mouseDragged(MouseEvent arg0){Main.panel.mouseDrag(arg0.getY());}
	
	@Override
	public void mouseReleased(MouseEvent arg0){Main.panel.mouseRelease();}

	@Override
	public void mouseWheelMoved(MouseWheelEvent arg0){Main.panel.scroll(arg0.getWheelRotation());}
	
	@Override
	public void mouseClicked(MouseEvent arg0){}
	@Override
	public void mouseEntered(MouseEvent arg0){}
	@Override
	public void mouseExited(MouseEvent arg0){}
	@Override
	public void mouseMoved(MouseEvent arg0){}
}

