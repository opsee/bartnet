-- name: insert-into-targets!
-- Inserts a new record into the targets table.
insert into targets (id, type, name) values (:id, :type, :name);

-- name: get-target-by-id
-- Retrieves a target by its id.
select * from targets where id=:id;

-----------------------------------------------------------------------------

-- name: insert-into-checks!
-- Inserts a new record into the checks table.
insert into checks (id, name, customer_id, "interval", target_id, last_run, check_spec) values
                  (:id, :name, :customer_id::UUID, :interval, :target_id, :last_run, :check_spec::jsonb);

-- name: update-check!
-- Updates an existing health_check record.
update checks set customer_id = :customer_id::UUID,
                  "interval" = :interval,
                  target_id = :target_id,
                  last_run = :last_run,
                  name = :name,
                  check_spec = :check_spec where id=:id;

-- name: get-check-by-id
-- Retrieves a health check record.
select * from checks where id=:id and customer_id=:customer_id::UUID;

-- name: get-checks-by-customer-id
-- Retrieves a list of health checks by env id.
select * from checks where customer_id=:customer_id::UUID;

-- name: delete-check-by-id!
-- Deletes a check record by id.
delete from checks where id=:id and customer_id=:customer_id::UUID;

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
