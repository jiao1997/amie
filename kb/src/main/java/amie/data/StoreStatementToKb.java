package amie.data;


import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;



public class StoreStatementToKb extends AbstractRDFHandler {
    @Override
    public void handleStatement(Statement st) {
       //prefix
        String subject = String.valueOf(st.getSubject());
        String object = String.valueOf(st.getObject());
        String predict = String.valueOf(st.getSubject());
        KB kb = new KB();
        kb.add(subject, object, predict);
    }
}
