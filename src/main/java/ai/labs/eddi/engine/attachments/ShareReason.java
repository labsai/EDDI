/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.Locale;

/**
 * The path a file is shared along. It is the {@code path} tag of
 * {@code eddi.attachment.shared} and is written to the audit entry of every
 * share.
 *
 * @since 6.7.0
 */
public enum ShareReason {
    /** An agent hands a file to an agent it converses with. */
    DELEGATION,
    /** A group task carries a file to the member that takes it. */
    GROUP_TASK,
    /** A member shares a file with its group discussion. */
    GROUP_SHARE,
    /** A team handoff carries a file. */
    HANDOFF,
    /** A file is sent to an A2A peer outside the deployment. */
    A2A_OUTBOUND,
    /** An MCP client attaches a file to a conversation it drives. */
    MCP,
    /** A nested group passes its parent discussion's files on. */
    NESTED_GROUP;

    /** The metric tag value: the name in lower case. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
