-- name: get-active-login-by-email
-- Gets only active logins with the given email addy
select * from logins where email=:email and active=true;

-- name: get-any-login-by-email
-- Gets a login by email regardless of active state
select * from logins where email=:email;

-- name: get-active-login-by-token
-- Gets only active logins with the given token
select * from logins, api_tokens
    where token=:token and
          logins.active=true and
          api_tokens.active=1 and
          api_tokens.login_id=logins.id;

-- name: get-active-login-by-id
-- Gets only active logins with the given id
select * from logins where id=:id and active=true;

-- name: insert-into-logins!
-- Inserts a new login for the given email address
insert into logins (email,name, password_hash, active,customer_id,verified) values (:email,:name,:password_hash,true,:customer_id,true);

-- name: update-login!
-- Updates the login
update logins set email=:email,name=:name,password_hash=:password_hash,verified=:verified,customer_id=:customer_id where id=:id;

-- name: make-superuser!
-- Flip the superuser bit.
update logins set admin=:admin where email=:email;

-- name: update-login-password!
-- Updates the password for a login
update logins set password_hash=:password_hash where id=:id;

-- name: deactivate-login!
-- Deactivates a login
update logins set active=false where id=:id;
-----------------------------------------------------------------------------

-- name: insert-into-environments!
-- Inserts a new environment
insert into environments (id, name, enabled) values (:id, :name, true);

-- name: link-environment-and-login!
-- Links an environment with a login
insert into environments_logins (environment_id,login_id) values (:environment_id,:login_id);

-- name: get-environment-for-login
-- Gets an environment for the specified login and env id
select environments.* from
    environments inner join environments_logins on
        environments.id=environments_logins.environment_id
    where environments.id=:id and environments_logins.login_id=:login_id and enabled=true;

-- name: get-environments-for-login
-- Gets all environments linked to the specified login
select environments.* from environments, environments_logins
    where environments.id=environments_logins.environment_id and
          environments_logins.login_id=:login_id and
          enabled=true;

-- name: update-environment!
-- Updates the mutable field of an environment, the name.
update environments set name=:name where id=:id;

-- name: toggle-environment!
-- Sets the disabled flag for an environment
update environments set enabled=:enabled where id=:id;

-- name: get-disabled-environment
-- Gets an environment that's been disabled
select * from environments where enabled=false and id=:id;

-----------------------------------------------------------------------------

-- name: insert-into-checks!
-- Inserts a new record into the checks table.
insert into checks (id,environment_id,name,description,group_type,group_id,check_type,check_request,check_interval,port) values
                          (:id,:environment_id,:name,:description,:group_type,:group_id,:check_type,:check_request,:check_interval,:port);

-- name: update-check!
-- Updates an existing health_check record.
update checks set name=:name, description=:description,
                  group_type=:group_type, group_id=:group_id,
                  check_type=:check_type, check_request=:check_request,
                  check_interval=:check_interval, port=:port where id=:id;

-- name: get-check-by-id
-- Retrieves a health check record.
select * from checks where id=:id;

-- name: get-checks-by-env-id
-- Retrieves a list of health checks by env id.
select * from checks where environment_id=:environment_id;

-- name: get-checks-by-customer-id
-- Retrieves all of the checks for a particular customer id.
select checks.* from checks inner join environments on checks.environment_id = environments.id
    inner join environments_logins on environments.id = environments_logins.environment_id
    inner join logins on environments_logins.login_id = logins.id
    where logins.customer_id = :customer_id;

-- name: delete-check-by-id!
-- Deletes a check record by id.
delete from checks where id=:id;

-----------------------------------------------------------------------------

-- name: insert-into-signups!
-- Inserts a new record into the signups table
insert into signups (email,name) values (:email,:name);

-- name: get-signups
select * from signups order by email limit :limit offset :offset;

-- name: get-signup-by-email
select * from signups where email=:email;

-- name: get-signups-with-activations-count
select count(*) as "count" from signups as s
  left outer join activations as a
  on s.email = a.email;

-- name: get-signups-with-activations
-- Get signups and their corresponding activation information.
select s.*, a.id as activation_id, a.used as activation_used from signups as s
  left outer join activations as a
  on s.email = a.email
  order by email limit :limit offset :offset;

-----------------------------------------------------------------------------

-- name: insert-into-activations!
-- Inserts a new record into the activations table
insert into activations (id,email,used,name) values (:id,:email,false,:name);

-- name: get-unused-activation
-- Gets an unused activation by its id
select * from activations where used=false and id=:id;

-- name: get-unused-activations
-- Gets all unused activations
select * from activations where used=false;

-- name: update-activations-set-used!
-- Sets the used flag for an activation record, ensuring that it cannot be reused.
update activations set used=true where id=:id;

-----------------------------------------------------------------------------

-- name: insert-into-teams!
-- Inserts a new record into the teams table
insert into teams (id,name) values (:id,:name);

-- name: get-team-by-id
-- Retrieves a team by its ID
select * from teams where id=:id;

-----------------------------------------------------------------------------

-- name: get-org-by-subdomain
-- Search for a subdomain by its name
select * from orgs where subdomain=:subdomain;

-- name: insert-into-orgs!
-- Inserts a new record into the orgs table
insert into orgs (name,subdomain) values (:name,:subdomain)