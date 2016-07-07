-- name: insert-into-checks!
-- Inserts a new record into the checks table.
insert into checks (id, name, customer_id, execution_group_id, "interval", target_id, check_spec, target_name, target_type, min_failing_time, min_failing_count) values
                  (:id, :name, :customer_id::UUID, :execution_group_id::UUID, :interval, :target_id, :check_spec::jsonb, :target_name, :target_type, coalesce(:min_failing_time, 90), coalesce(:min_failing_count, 1));

-- name: update-check!
-- Updates an existing health_check record.
update checks set customer_id = :customer_id::UUID,
                  execution_group_id = :execution_group_id::UUID,
                  "interval" = :interval,
                  target_id = :target_id,
                  name = :name,
                  check_spec = :check_spec,
                  target_name = :target_name,
                  min_failing_count = coalesce(:min_failing_count, 1),
                  min_failing_time = coalesce(:min_failing_time, 90),
                  target_type = :target_type where id=:id and deleted=false;

-- name: get-check-by-id
-- Retrieves a health check record.
select checks.*, coalesce(states.failing_count, 0) as failing_count, coalesce(states.response_count, 0) as response_count, coalesce(states.state_name, 'INITIALIZING') as state from checks left outer join check_states as states on (checks.id = states.check_id) where checks.id=:id and checks.customer_id=:customer_id::UUID and checks.deleted=false;

-- name: get-checks-by-customer-id
-- Retrieves a list of health checks by env id.
select checks.*, coalesce(states.failing_count, 0) as failing_count, coalesce(states.response_count, 0) as response_count, coalesce(states.state_name, 'INITIALIZING') as state from checks left outer join check_states as states on (checks.id = states.check_id) where checks.customer_id=:customer_id::UUID and checks.deleted=false;

-- name: get-global-checks-by-execution-group-id
-- Retrieves a list of health checks by execution group id.
select * from checks where execution_group_id=:execution_group_id::UUID and deleted=false;

-- name: get-checks-by-execution-group-id
-- Retrieves a list of health checks by execution group id.
select checks.*, coalesce(states.failing_count, 0) as failing_count, coalesce(states.response_count, 0) as response_count, coalesce(states.state_name, 'INITIALIZING') as state from checks left outer join check_states as states on (checks.id = states.check_id) where checks.execution_group_id=:execution_group_id::UUID and checks.customer_id=:customer_id::UUID and checks.deleted=false;

-- name: delete-check-by-id!
-- Deletes a check record by id.
update checks set deleted=true where id=:id and customer_id=:customer_id::UUID;

-----------------------------------------------------------------------------

-- name: get-assertions
-- Retrieves assertions for a check by their check_id and customer_id
select * from assertions where customer_id=:customer_id::UUID and check_id=:check_id;

-- name: insert-into-assertions!
-- Insert an assertion
insert into assertions (check_id, customer_id, key, value, relationship, operand) values
                  (:check_id, :customer_id::UUID, :key, :value, :relationship::relationship_type, :operand);

-- name: delete-assertions!
-- Delete an assertion by the associated customer ID and check ID
delete from assertions where customer_id=:customer_id::UUID and check_id=:check_id;
