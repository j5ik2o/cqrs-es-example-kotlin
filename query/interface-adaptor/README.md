# query:interface-adaptor

This module implements the Read API. It exposes the six queries from the shared GroupChat schema,
reads the MySQL read model through Spring `JdbcTemplate`, and filters every query by the viewer's
`userAccountId`. The `NaiveDateTime` scalar serializes `LocalDateTime` values as ISO 8601
date-times.
