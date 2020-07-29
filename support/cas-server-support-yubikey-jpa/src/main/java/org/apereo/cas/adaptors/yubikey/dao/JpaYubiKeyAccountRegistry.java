package org.apereo.cas.adaptors.yubikey.dao;

import org.apereo.cas.adaptors.yubikey.JpaYubiKeyAccount;
import org.apereo.cas.adaptors.yubikey.YubiKeyAccount;
import org.apereo.cas.adaptors.yubikey.YubiKeyAccountValidator;
import org.apereo.cas.adaptors.yubikey.YubiKeyDeviceRegistrationRequest;
import org.apereo.cas.adaptors.yubikey.YubiKeyRegisteredDevice;
import org.apereo.cas.adaptors.yubikey.registry.BaseYubiKeyAccountRegistry;
import org.apereo.cas.util.CollectionUtils;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This is {@link JpaYubiKeyAccountRegistry}.
 *
 * @author Misagh Moayyed
 * @since 5.2.0
 */
@EnableTransactionManagement(proxyTargetClass = true)
@Transactional(transactionManager = "transactionManagerYubiKey")
@Slf4j
public class JpaYubiKeyAccountRegistry extends BaseYubiKeyAccountRegistry {

    private static final String SELECT_QUERY = "SELECT r from " + JpaYubiKeyAccount.class.getSimpleName() + " r ";

    private static final String SELECT_ACCOUNT_QUERY = SELECT_QUERY.concat(" WHERE r.username = :username");

    @PersistenceContext(unitName = "yubiKeyEntityManagerFactory")
    private transient EntityManager entityManager;

    public JpaYubiKeyAccountRegistry(final YubiKeyAccountValidator accountValidator) {
        super(accountValidator);
    }

    @Override
    public boolean registerAccountFor(final YubiKeyDeviceRegistrationRequest request) {
        val accountValidator = getAccountValidator();
        if (accountValidator.isValid(request.getUsername(), request.getToken())) {
            val yubikeyPublicId = getCipherExecutor().encode(accountValidator.getTokenPublicId(request.getToken()));

            val results = this.entityManager.createQuery(SELECT_ACCOUNT_QUERY, JpaYubiKeyAccount.class)
                .setParameter("username", request.getUsername())
                .setMaxResults(1)
                .getResultList();

            val device = YubiKeyRegisteredDevice.builder()
                .id(System.currentTimeMillis())
                .name(request.getName())
                .publicId(yubikeyPublicId)
                .registrationDate(ZonedDateTime.now(Clock.systemUTC()))
                .build();

            if (results.isEmpty()) {
                val jpaAccount = JpaYubiKeyAccount.builder()
                    .username(request.getUsername())
                    .devices(CollectionUtils.wrapList(device))
                    .build();
                return this.entityManager.merge(jpaAccount) != null;
            }
            val jpaAccount = results.get(0);
            jpaAccount.getDevices().add(device);
            return this.entityManager.merge(jpaAccount) != null;
        }
        return false;
    }

    @Override
    public Collection<? extends YubiKeyAccount> getAccounts() {
        try {
            return this.entityManager.createQuery(SELECT_QUERY, JpaYubiKeyAccount.class)
                .getResultList()
                .stream()
                .peek(it -> {
                    val devices = it.getDevices().stream()
                        .filter(device -> getCipherExecutor().decode(device.getPublicId()) != null)
                        .collect(Collectors.toCollection(ArrayList::new));
                    it.setDevices(devices);
                })
                .collect(Collectors.toList());
        } catch (final NoResultException e) {
            LOGGER.debug("No registration record could be found");
        } catch (final Exception e) {
            LOGGER.debug(e.getMessage(), e);
        }
        return new ArrayList<>(0);
    }

    @Override
    public Optional<? extends YubiKeyAccount> getAccount(final String uid) {
        try {
            val account = this.entityManager.createQuery(SELECT_ACCOUNT_QUERY, JpaYubiKeyAccount.class)
                .setParameter("username", uid)
                .getSingleResult();
            val devices = account.getDevices()
                .stream()
                .map(device -> device.setPublicId(getCipherExecutor().decode(device.getPublicId())))
                .collect(Collectors.toCollection(ArrayList::new));

            entityManager.detach(account);
            account.setDevices(devices);
            return Optional.of(account);
        } catch (final NoResultException e) {
            LOGGER.debug("No registration record could be found", e);
        } catch (final Exception e) {
            LOGGER.debug(e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public void delete(final String uid) {
        val account = this.entityManager.createQuery(SELECT_ACCOUNT_QUERY, JpaYubiKeyAccount.class)
            .setParameter("username", uid)
            .getSingleResult();
        entityManager.remove(account);
        LOGGER.debug("Deleted [{}] record(s)", account);
    }

    @Override
    public void deleteAll() {
        this.entityManager.createQuery(SELECT_QUERY).getResultList().forEach(r -> entityManager.remove(r));
    }
}
