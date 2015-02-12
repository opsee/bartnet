-- name: get-active-login-by-email
-- Gets only active logins with the given email addy
select * from logins where email=:email and active=true;

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
insert into logins (email, password_hash, active) values (:email,:password_hash,true);

-- name: update-login!
-- Updates the login, but will not modify the password
update logins set email=:email, onboard=:onboard where id=:id;

-- name: update-login-password!
-- Updates the password for a login
update logins set password_hash=:password_hash where id=:id;

-----------------------------------------------------------------------------

-- name: insert-into-environments!
-- Inserts a new environment
insert into environments (id, name, enabled) values (:id, :name, true);

-- name: link-environment-and-login!
-- Links an environment with a login
insert into environments_logins (environment_id,login_id) values (:env_id,:login_id);

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

