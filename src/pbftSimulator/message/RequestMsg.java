package pbftSimulator.message;

public class RequestMsg extends Message {

	public static int proposalCount = 0;
	
	public String o;
	
	public long t;			
	
	public int c;			
	
	//消息结构
	//<REQUEST, o, t, c>:o表示客户端请求的操作;t表示客户端请求时间戳;c表示客户端id
	public RequestMsg(String o, long t, int c, int sndId, int rcvId, long receiveTime) {
		super(sndId, rcvId, receiveTime);
		this.type = REQUEST;
		this.len = REQMSGLEN;
		this.o = o;
		this.t = t;
		this.c = c;
		proposalCount++;
	}
	
	public boolean equals(Object obj) {
        if (obj instanceof RequestMsg) {
        	RequestMsg msg = (RequestMsg) obj;
            return (o.equals(msg.o) && t == msg.t && c == msg.c);
        }
        return super.equals(obj);
    }
        
    public int hashCode() {
        String str = o + t + c;
        return str.hashCode();
    }
    
    public String toString() {
    	return super.toString() + "客户端请求时间戳:"+t+";客户端id:"+c;
    }
	
}
