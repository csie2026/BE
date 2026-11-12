package com.ggmount.hiking.controller;

import com.ggmount.hiking.service.HikingActivityConflict;
import com.ggmount.hiking.service.HikingActivityService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// 서비스 Mock이 던진 구조화된 DB 예외의 변환만 검사한다. 실제 제약 위반·트랜잭션 롤백·HTTP 보안 필터는 실행하지 않는다.
class HikingActivityControllerTests {
    private static final String SESSION = "hiking_activity_session_unique";

    private HikingActivityController controller(DataIntegrityViolationException failure) {
        var service = mock(HikingActivityService.class);
        when(service.create(null, null)).thenThrow(failure);
        return new HikingActivityController(service);
    }

    private PSQLException postgres(String state, String constraint) {
        var fields = mock(ServerErrorMessage.class);
        when(fields.getSQLState()).thenReturn(state);
        when(fields.getConstraint()).thenReturn(constraint);
        return new PSQLException(fields);
    }

    private void duplicate(DataIntegrityViolationException failure) {
        var conflict = assertThrows(HikingActivityConflict.class, () -> controller(failure).create(null, null));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("duplicate_hiking_activity", conflict.code());
    }

    @Test void missingHibernateNameUsesStructuredPostgresFields() {
        duplicate(new DataIntegrityViolationException("test",
            new ConstraintViolationException("test", postgres("23505", SESSION), null)));
    }

    @Test void directPostgresCauseUsesStructuredFields() {
        duplicate(new DataIntegrityViolationException("test", postgres("23505", SESSION)));
    }

    @Test void existingHibernateMappingsRemainSupported() {
        duplicate(new DataIntegrityViolationException("test",
            new ConstraintViolationException("test", new java.sql.SQLException(), SESSION)));
        var failure = new DataIntegrityViolationException("test", new ConstraintViolationException("test",
            new java.sql.SQLException(), "hiking_activities_member_id_client_request_id_key"));
        var conflict = assertThrows(HikingActivityConflict.class, () -> controller(failure).create(null, null));
        assertEquals("client_request_conflict", conflict.code());
    }

    @Test void unrelatedStatesConstraintsAndSimilarNamesPropagateUnchanged() {
        for (String state : new String[]{"23505", "23503", "23502"}) {
            for (String name : new String[]{SESSION, "other_unique", SESSION + "_other"}) {
                if (state.equals("23505") && name.equals(SESSION)) continue;
                var failure = new DataIntegrityViolationException("test", postgres(state, name));
                assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                    () -> controller(failure).create(null, null)));
            }
        }
    }

    @Test void knownOtherHibernateNameDoesNotUseFallback() {
        // Hibernate 제약 이름이 있으면 하위 PostgreSQL 정보가 알려진 세션 제약이어도 보완 판별을 적용하지 않는 경계다.
        var failure = new DataIntegrityViolationException("test", new ConstraintViolationException("test",
            postgres("23505", SESSION), SESSION + "_other"));
        assertSame(failure, assertThrows(DataIntegrityViolationException.class,
            () -> controller(failure).create(null, null)));
    }

    @Test void absentStructuredFieldsPropagateUnchanged() {
        for (var cause : new Throwable[]{postgres("23505", null),
            new PSQLException("test", org.postgresql.util.PSQLState.UNIQUE_VIOLATION)}) {
            var failure = new DataIntegrityViolationException("test", cause);
            assertSame(failure, assertThrows(DataIntegrityViolationException.class,
                () -> controller(failure).create(null, null)));
        }
    }
}
