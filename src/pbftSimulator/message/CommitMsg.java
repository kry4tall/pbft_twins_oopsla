package pbftSimulator.message;

public class CommitMsg extends Message {
	
	public int v;				
	
	public int n;			
	
	public String d;	
	
	public int i;
	
	//消息结构
	//<COMMIT, v, n, d, i>:v表示视图编号;n表示序列号;d表示request消息的摘要;i表示节点id
	public CommitMsg(String proposalHash, int v, int n, String d, int i, int sndId, int rcvId, long receiveTime) {
		super(sndId, rcvId, receiveTime);
		this.proposalHash = proposalHash;
		this.type = COMMIT;
		this.len = COMMSGLEN;
		this.v = v;
		this.n = n;
		this.d = d;
		this.i = i;
	}
	
	public Message copy(int rcvId, long receiveTime) {
		return new CommitMsg(proposalHash, v, n, new String(d), i, sndId, rcvId, receiveTime);
	}
	
	public boolean equals(Object obj) {
        if (obj instanceof CommitMsg) {
        	CommitMsg msg = (CommitMsg) obj;
            return (proposalHash.equals(msg.proposalHash) && v == msg.v && n == msg.n && d.equals(msg.d) && i == msg.i);
        }
        return super.equals(obj);
    }
        
    public int hashCode() {
        String str = "" + v + n + d + i;
        return str.hashCode();
    }
    
    public String toString() {
    	return super.toString() + "视图编号:"+v+";序列号:"+n;
    }
}
