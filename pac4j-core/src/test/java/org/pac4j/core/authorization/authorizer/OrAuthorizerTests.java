package org.pac4j.core.authorization.authorizer;

import org.junit.Before;
import org.junit.Test;
import org.pac4j.core.context.MockWebContext;
import org.pac4j.core.context.session.MockSessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.pac4j.core.authorization.authorizer.OrAuthorizer.or;
import static org.pac4j.core.authorization.authorizer.RequireAnyRoleAuthorizer.requireAnyRole;

/**
 * Tests {@link OrAuthorizer}
 *
 * @author Sergey Morgunov
 * @since 3.4.0
 */
@SuppressWarnings("PMD.TooManyStaticImports")
public class OrAuthorizerTests {

    private List<UserProfile> profiles = new ArrayList<>();

    @Before
    public void setUp() {
        var profile = new CommonProfile();
        profile.addRole("profile_role");
        profiles.add(profile);
    }

    @Test
    public void testDisjunctionAuthorizer1() {
        final Authorizer authorizer = or(
            requireAnyRole("profile_role2")
        );
        assertFalse(authorizer.isAuthorized(MockWebContext.create(), new MockSessionStore(), profiles));
    }

    @Test
    public void testDisjunctionAuthorizer2() {
        final Authorizer authorizer = or(
            requireAnyRole("profile_role")
        );
        assertTrue(authorizer.isAuthorized(MockWebContext.create(), new MockSessionStore(), profiles));
    }
}
