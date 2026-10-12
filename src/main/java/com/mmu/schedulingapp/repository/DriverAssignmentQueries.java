package com.mmu.schedulingapp.repository;

/** Shared SQL predicates; authorization stays in the query, including the atomic status write. */
public final class DriverAssignmentQueries {
    private DriverAssignmentQueries() { }

    // GROUP BY keeps the integrity view materialized on MySQL, where a status UPDATE
    // cannot directly select its target deliveries table in a correlated subquery.
    public static final String VALID_ROUTE = """
            NOT EXISTS (SELECT 1 FROM (
                SELECT members.route_id FROM route_deliveries members
                JOIN routes owner ON owner.route_id = members.route_id
                LEFT JOIN deliveries member_delivery ON member_delivery.delivery_id = members.delivery_id
                LEFT JOIN (SELECT delivery_id, COUNT(*) AS amount FROM route_deliveries
                           GROUP BY delivery_id) memberships ON memberships.delivery_id = members.delivery_id
                WHERE owner.operator_id IN (SELECT operator_id FROM user_accounts WHERE id = :accountId)
                GROUP BY members.route_id
                HAVING SUM(CASE WHEN member_delivery.operator_id IS NULL
                    OR member_delivery.operator_id <> owner.operator_id OR memberships.amount <> 1
                    THEN 1 ELSE 0 END) > 0
            ) invalid_routes WHERE invalid_routes.route_id = r.route_id)
            """;
    public static final String OWNED_ROUTES = """
            SELECT r.* FROM routes r JOIN user_accounts a ON a.operator_id = r.operator_id
            WHERE a.id = :accountId AND a.enabled = TRUE AND a.role = 'DRIVER' AND a.auth_revision = :authRevision AND a.password_change_required = FALSE AND
            """ + VALID_ROUTE;
    public static final String OWNED_DELIVERY = """
            EXISTS (
                SELECT 1 FROM route_deliveries rd JOIN routes r ON r.route_id = rd.route_id
                JOIN user_accounts a ON a.operator_id = r.operator_id
                WHERE rd.delivery_id = d.delivery_id AND d.operator_id = r.operator_id
                    AND a.id = :accountId AND a.enabled = TRUE AND a.role = 'DRIVER' AND a.auth_revision = :authRevision AND a.password_change_required = FALSE
                    AND (SELECT COUNT(*) FROM route_deliveries memberships
                         WHERE memberships.delivery_id = d.delivery_id) = 1 AND
            """ + VALID_ROUTE + ")";
    public static final String SELECT_DELIVERY = "SELECT d.* FROM deliveries d WHERE d.delivery_id = :deliveryId AND " + OWNED_DELIVERY;
    public static final String UPDATE_STATUS = """
            UPDATE deliveries d SET delivery_status = :status
            WHERE d.delivery_id = :deliveryId AND d.delivery_status = 'IN_PROGRESS' AND
            """ + OWNED_DELIVERY;
}
