package io.sls.user;

/**
 * User: jarisch
 * Date: 28.08.12
 * Time: 12:06
 */
public interface IUser {
    String getUsername();

    String getPassword();

    String getDisplayName();

    String getEmail();
}
