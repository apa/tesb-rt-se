/*
 * #%L
 * Service Activity Monitoring :: Server
 * %%
 * Copyright (C) 2011 - 2012 Talend Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.talend.esb.sam.server.persistence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcDaoSupport;
import org.talend.esb.sam.common.event.Event;
import org.talend.esb.sam.common.event.MessageInfo;
import org.talend.esb.sam.common.event.Originator;
import org.talend.esb.sam.common.event.persistence.EventRepository;
import org.talend.esb.sam.server.persistence.dialects.DatabaseDialect;

/**
 * The Class EventRepositoryImpl is implementing the event repository logic.
 */
public class EventRepositoryImpl extends SimpleJdbcDaoSupport implements EventRepository {

    private static final Logger LOG = Logger.getLogger(EventRepositoryImpl.class.getName());

    private DatabaseDialect dialect;

    /**
     * Sets the database dialect.
     *
     * @param dialect the database dialect
     */
    public void setDialect(DatabaseDialect dialect) {
        this.dialect = dialect;
    }

    /* (non-Javadoc)
     * @see org.talend.esb.sam.common.event.persistence.EventRepository#writeEvent(org.talend.esb.sam.common.event.Event)
     */
    @Override
    public void writeEvent(Event event) {
        Originator originator = event.getOriginator();
        MessageInfo messageInfo = event.getMessageInfo();

        long id = dialect.getIncrementer().nextLongValue();
        event.setPersistedId(id);

        getSimpleJdbcTemplate()
            .update("insert into EVENTS (ID, EI_TIMESTAMP, EI_EVENT_TYPE,"
                    + " ORIG_PROCESS_ID, ORIG_IP, ORIG_HOSTNAME, "
                    + " ORIG_CUSTOM_ID, ORIG_PRINCIPAL,"
                    + " MI_MESSAGE_ID, MI_FLOW_ID, MI_PORT_TYPE,"
                    + " MI_OPERATION_NAME, MI_TRANSPORT_TYPE,"
                    + " CONTENT_CUT, MESSAGE_CONTENT) "
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    event.getPersistedId(), event.getTimestamp(), event.getEventType().toString(),
                    originator.getProcessId(), originator.getIp(), originator.getHostname(),
                    originator.getCustomId(), originator.getPrincipal(),
                    messageInfo.getMessageId(), messageInfo.getFlowId(), messageInfo.getPortType(),
                    messageInfo.getOperationName(), messageInfo.getTransportType(),
                    event.isContentCut(), event.getContent());

        writeCustomInfo(event);

        if (dialect.getName().equals("derbyDialect") || 
                dialect.getName().equals("h2Dialect")) {
	        String flowQuery = "SELECT count(*) FROM FLOWS WHERE ID = '" + messageInfo.getFlowId() + "'";
	        int rowCount = getSimpleJdbcTemplate().queryForInt(flowQuery);
	        if (rowCount <= 0) {
	            getSimpleJdbcTemplate().update(
	                "insert into FLOWS (ID, FI_TIMESTAMP) values (?,?)",
	                messageInfo.getFlowId(), event.getTimestamp());
	        } else {
	            getSimpleJdbcTemplate().update(
	                "update FLOWS set FI_TIMESTAMP=? where ID=?",
	                event.getTimestamp(), messageInfo.getFlowId());
	        }
        }

        if (LOG.isLoggable(Level.INFO)) {
            LOG.info("event [message_id=" + messageInfo.getMessageId() + "] persist to Database successful."
                + " ID=" + id);
        }
    }

    /* (non-Javadoc)
     * @see org.talend.esb.sam.common.event.persistence.EventRepository#readEvent(long)
     */
    @Override
    public Event readEvent(long id) {
        RowMapper<Event> rowMapper = new EventRowMapper();
        Event event = getSimpleJdbcTemplate()
            .queryForObject("select * from EVENTS where ID=" + id, rowMapper);
        event.getCustomInfo().clear();
        event.getCustomInfo().putAll(readCustomInfo(id));
        return event;
    }

    /**
     * write CustomInfo list into table.
     *
     * @param event the event
     */
    private void writeCustomInfo(Event event) {
        // insert customInfo (key/value) into DB
        for (Map.Entry<String, String> customInfo : event.getCustomInfo().entrySet()) {
            long cust_id = dialect.getIncrementer().nextLongValue();
            getSimpleJdbcTemplate()
                .update("insert into EVENTS_CUSTOMINFO (ID, EVENT_ID, CUST_KEY, CUST_VALUE)"
                        + " values (?,?,?,?)",
                            cust_id, event.getPersistedId(), customInfo.getKey(), customInfo.getValue());
        }
    }

    /**
     * read CustomInfo list from table.
     *
     * @param eventId the event id
     * @return the map
     */
    private Map<String, String> readCustomInfo(long eventId) {
        List<Map<String, Object>> rows = getSimpleJdbcTemplate()
            .queryForList("select * from EVENTS_CUSTOMINFO where EVENT_ID=" + eventId);
        Map<String, String> customInfo = new HashMap<String, String>(rows.size());
        for (Map<String, Object> row : rows) {
            customInfo.put((String)row.get("CUST_KEY"), (String)row.get("CUST_VALUE"));
        }
        return customInfo;
    }
}
