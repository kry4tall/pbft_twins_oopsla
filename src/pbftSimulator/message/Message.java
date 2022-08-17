package pbftSimulator.message;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import pbftSimulator.Simulator;

public class Message {
	
	public static final int REQUEST = 0;		
	
	public static final int PREPREPARE = 1;
	
	public static final int PREPARE = 2;
	
	public static final int COMMIT = 3;
	
	public static final int REPLY = 4;
	
	public static final int CHECKPOINT = 5;
	
	public static final int VIEWCHANGE = 6;
	
	public static final int NEWVIEW = 7;
	
	public static final int TIMEOUT = 8;					//用来提醒节点超时的虚拟消息
	
	public static final int CLITIMEOUT = 9;					//用来提醒客户端超时的虚拟消息
	
	public static final long PRQMSGLEN = 0;					//PreRequest消息是虚拟消息
	
	public static final long REQMSGLEN = 100;				//Request消息的大小(bytes),可按实际情况设置
	
	public static final long PPRMSGLEN = 4 + REQMSGLEN;		//RrePrepare消息的大小
	
	public static final long PREMSGLEN = 36;				//Prepare消息的大小
	
	public static final long COMMSGLEN = 36;				//Commit消息的大小
	
	public static final long REPMSGLEN = 16;				//Reply消息的大小
	
	public static final long CKPMSGBASELEN = 4;				//CheckPoint消息的基础大小（还需要动态加上s集合大小）
	
	public static final long VCHMSGBASELEN = 4;				//ViewChange消息的基础大小
	
	public static final long NEVMSGBASELEN = 3;				//NewView消息的基础大小
	
	public static final long LASTREPLEN = REPMSGLEN - 3;	//LastReply的大小
	
	public static final long TIMMSGLEN = 0;					//TimeOut消息是虚拟消息
	
	public static final long CLTMSGLEN = 0;					//CliTimeOut消息是虚拟消息
	
	public static Comparator<Message> cmp = new Comparator<Message>(){
		@Override
		public int compare(Message c1, Message c2) {
			return (int) (c1.receiveTime - c2.receiveTime);
		}
	};
	
	public int type;				//消息类型	
	
	public int sndId;				//消息发送端id

	public int rcvId;  				//消息接收端id

	public long sendTime;             //消息发送时间

	public long receiveTime;  			//消息接收时间
	
	public long len;				//消息大小

	public String proposalHash;
	
	public Message(int sndId, int rcvId, long receiveTime) {
		this.sndId = sndId;
		this.rcvId = rcvId;
		this.receiveTime = receiveTime;
	}

	public boolean needHash(String msgString){
		return msgString.contains("Prepare") || msgString.contains("Commit") || msgString.contains("Reply");
	}
	
	public void print(String tag) {
		if(!Simulator.SHOWDETAILINFO) return;
		String prefix = "##"+tag+"##";
		if(tag.contains("Send"))
		{
			if(needHash(toString())) {
				int index = toString().indexOf("消息类型");
				System.out.println(prefix + "当前时间戳:" + sendTime + ",消息将在时间戳:" + receiveTime + "被接收;" + toString().substring(index) + ";Proposal hash:" + proposalHash);
			}
			else{
				int index = toString().indexOf("消息类型");
				System.out.println(prefix + "当前时间戳:" + sendTime + ",消息将在时间戳:" + receiveTime + "被接收;" + toString().substring(index));
			}
		}
		else if(tag.contains("Receive")){
			if(needHash(toString())){
				int index = toString().indexOf("消息类型");
				System.out.println(prefix + "当前时间戳:"+ receiveTime +";" + toString().substring(index) + ";Proposal hash:" + proposalHash);
			}
			else{
				int index = toString().indexOf("消息类型");
				System.out.println(prefix + "当前时间戳:"+ receiveTime +";" + toString().substring(index));
			}
		}
		else{
			System.out.println(prefix + toString());
		}
	}
	
	public static long accumulateLen(Set<Message> set) {
		long len = 0L;
		if(set != null) {
			for(Message m : set) {
				len += m.len;
			}
		}
		return len;
	}
	
	public static long accumulateLen(Map<Integer, Set<Message>> map) {
		long len = 0L;
		if(map != null) {
			for(Integer n : map.keySet()) {
				len += accumulateLen(map.get(n));
			}
		}
		return len;
	}
	
	public Message copy(int rcvId, long receiveTime) {
		return new Message(sndId, rcvId, receiveTime);
	}
	
	public boolean equals(Object obj) {
        if (obj instanceof Message) {
        	Message msg = (Message) obj;
            return (proposalHash.equals(msg.proposalHash) && type == msg.type && sndId == msg.sndId && rcvId == msg.rcvId && receiveTime == msg.receiveTime);
        }
        return super.equals(obj);
    }
        
    public int hashCode() {
        String str = "" + type + sndId + rcvId + receiveTime;
        return str.hashCode();
    }
    
    public String toString() {
		String[] typeName = {"Request","PrePrepare","Prepare","Commit","Reply"
				,"CheckPoint","ViewChange","NewView","TimeOut","CliTimeOut"};
		return "消息接收时间戳:"+receiveTime+";"+"消息类型:"+typeName[type]+";发送者id:"
				+sndId+";接收者id:"+rcvId+";";
    }

}
