package ai.labs.eddi.configs.migration.model;

import java.util.Date;

public class MigrationLog {
    private String name;
    private boolean finished;
    private Date timestamp;

    public MigrationLog(String name) {
        this.name = name;
        finished = true;
        timestamp = new Date(System.currentTimeMillis());
    }

    public MigrationLog() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setFinished(boolean finished) {
        this.finished = finished;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
}
