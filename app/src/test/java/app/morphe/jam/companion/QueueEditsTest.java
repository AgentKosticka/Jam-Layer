package app.morphe.jam.companion;
import app.morphe.jam.ipc.QueueEdits;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class QueueEditsTest {
    private JSONObject state(int revision,String order)throws Exception{JSONArray rows=new JSONArray();for(char id:order.toCharArray())rows.put(new JSONObject().put("id",String.valueOf(id)));return new JSONObject().put("revision","session:"+revision).put("items",rows).put("autoplay",new JSONArray());}
    private JSONObject move(String item,String anchor,boolean after)throws Exception{return new JSONObject().put("op","MOVE").put("id","request").put("item",item).put("anchor",anchor).put("after",after);}
    private String order(QueueEdits edits)throws Exception{StringBuilder b=new StringBuilder();JSONArray rows=edits.view().getJSONArray("items");for(int i=0;i<rows.length();i++)b.append(rows.getJSONObject(i).getString("id"));return b.toString();}
    @Test public void oldPollCannotUndoPendingOrAcknowledgedMove()throws Exception{
        QueueEdits edits=new QueueEdits();edits.accept(state(1,"abcd"));edits.add(move("a","c",true));assertEquals("bcad",order(edits));
        edits.accept(state(1,"abcd"));assertEquals("bcad",order(edits));edits.complete(state(2,"bcad"));assertEquals("bcad",order(edits));
        edits.accept(state(1,"abcd"));assertEquals("bcad",order(edits));
    }
    @Test public void rapidMovesRemainVisibleAndRebaseToConfirmedRevision()throws Exception{
        QueueEdits edits=new QueueEdits();edits.accept(state(1,"abcd"));edits.add(move("a","c",true));edits.add(move("d","b",false));assertEquals("dbca",order(edits));
        assertEquals("c",edits.next().getString("target"));edits.complete(state(2,"bcad"));assertEquals("dbca",order(edits));
        assertEquals("session:2",edits.next().getString("revision"));assertEquals("b",edits.next().getString("target"));edits.complete(state(3,"dbca"));assertEquals("dbca",order(edits));
    }
    @Test public void rejectedRemovalRollsBackOnlyAfterExplicitResponse()throws Exception{
        QueueEdits edits=new QueueEdits();edits.accept(state(1,"abc"));edits.add(new JSONObject().put("op","REMOVE").put("item","b"));
        edits.accept(state(2,"cab"));assertEquals("ac",order(edits));edits.complete(new JSONObject().put("ok",false));assertEquals("cab",order(edits));
    }
}
