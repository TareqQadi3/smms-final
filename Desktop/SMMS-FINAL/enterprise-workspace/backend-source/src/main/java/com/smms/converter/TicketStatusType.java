package com.smms.converter;

import com.smms.enums.TicketStatus;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.*;

/**
 * Custom Hibernate UserType for TicketStatus.
 *
 * Problem: TicketStatus Java enum uses uppercase names (NEW, IN_PROGRESS) but
 * PostgreSQL ticket_status enum uses lowercase values (new, in_progress).
 * When Hibernate binds a TicketStatus value as Types.VARCHAR, PostgreSQL raises:
 *   "operator does not exist: ticket_status = character varying"
 *
 * Fix: bind the parameter using Types.OTHER (untyped/unknown).
 * PostgreSQL's prepared-statement type inference then resolves the untyped literal
 * to the expected ticket_status type based on the column's type context, and the
 * lowercase dbValue matches the enum value in the database.
 */
public class TicketStatusType implements UserType<TicketStatus> {

    @Override
    public int getSqlType() {
        return Types.OTHER;   // untyped → PostgreSQL resolves from context
    }

    @Override
    public Class<TicketStatus> returnedClass() {
        return TicketStatus.class;
    }

    @Override
    public boolean equals(TicketStatus x, TicketStatus y) {
        return x == y;
    }

    @Override
    public int hashCode(TicketStatus x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    public TicketStatus nullSafeGet(ResultSet rs, int position,
                                    SharedSessionContractImplementor session,
                                    Object owner) throws SQLException {
        String value = rs.getString(position);
        return (value == null) ? null : TicketStatus.fromDbValue(value);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, TicketStatus value, int index,
                             SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, value.getDbValue(), Types.OTHER);
        }
    }

    @Override
    public TicketStatus deepCopy(TicketStatus value) {
        return value;   // enum is immutable
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(TicketStatus value) {
        return value;
    }

    @Override
    public TicketStatus assemble(Serializable cached, Object owner) {
        return (TicketStatus) cached;
    }
}
