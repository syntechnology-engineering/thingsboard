/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.device;


import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.core.SecurityMode;
import org.eclipse.leshan.core.util.SecurityUtil;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.device.credentials.BasicMqttCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MBootstrapClientCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MClientCredential;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MDeviceCredentials;
import org.thingsboard.server.common.data.device.credentials.lwm2m.LwM2MBootstrapClientCredential;
import org.thingsboard.server.common.data.device.credentials.lwm2m.PSKClientCredential;
import org.thingsboard.server.common.data.device.credentials.lwm2m.PSKBootstrapClientCredential;
import org.thingsboard.server.common.data.device.credentials.lwm2m.RPKClientCredential;
import org.thingsboard.server.common.data.device.credentials.lwm2m.RPKBootstrapClientCredential;
import org.thingsboard.server.common.data.device.credentials.lwm2m.X509ClientCredential;
import org.thingsboard.server.common.data.device.credentials.lwm2m.X509BootstrapClientCredential;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.msg.EncryptionUtil;
import org.thingsboard.server.dao.entity.AbstractEntityService;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.exception.DeviceCredentialsValidationException;
import org.thingsboard.server.dao.service.DataValidator;
import static org.thingsboard.server.common.data.CacheConstants.DEVICE_CREDENTIALS_CACHE;
import static org.thingsboard.server.dao.service.Validator.validateId;
import static org.thingsboard.server.dao.service.Validator.validateString;

@Service
@Slf4j
public class DeviceCredentialsServiceImpl extends AbstractEntityService implements DeviceCredentialsService {

    @Autowired
    private DeviceCredentialsDao deviceCredentialsDao;

    @Autowired
    private DeviceService deviceService;

    @Override
    public DeviceCredentials findDeviceCredentialsByDeviceId(TenantId tenantId, DeviceId deviceId) {
        log.trace("Executing findDeviceCredentialsByDeviceId [{}]", deviceId);
        validateId(deviceId, "Incorrect deviceId " + deviceId);
        return deviceCredentialsDao.findByDeviceId(tenantId, deviceId.getId());
    }

    @Override
    @Cacheable(cacheNames = DEVICE_CREDENTIALS_CACHE, key = "'deviceCredentials_' + #credentialsId", unless = "#result == null")
    public DeviceCredentials findDeviceCredentialsByCredentialsId(String credentialsId) {
        log.trace("Executing findDeviceCredentialsByCredentialsId [{}]", credentialsId);
        validateString(credentialsId, "Incorrect credentialsId " + credentialsId);
        return deviceCredentialsDao.findByCredentialsId(TenantId.SYS_TENANT_ID, credentialsId);
    }

    @Override
    @CacheEvict(cacheNames = DEVICE_CREDENTIALS_CACHE, keyGenerator = "previousDeviceCredentialsId", beforeInvocation = true)
    public DeviceCredentials updateDeviceCredentials(TenantId tenantId, DeviceCredentials deviceCredentials) {
        return saveOrUpdate(tenantId, deviceCredentials);
    }

    @Override
    public DeviceCredentials createDeviceCredentials(TenantId tenantId, DeviceCredentials deviceCredentials) {
        return saveOrUpdate(tenantId, deviceCredentials);
    }

    private DeviceCredentials saveOrUpdate(TenantId tenantId, DeviceCredentials deviceCredentials) {
        if (deviceCredentials.getCredentialsType() == null) {
            throw new DataValidationException("Device credentials type should be specified");
        }
        formatCredentials(deviceCredentials);
        log.trace("Executing updateDeviceCredentials [{}]", deviceCredentials);
        credentialsValidator.validate(deviceCredentials, id -> tenantId);
        try {
            return deviceCredentialsDao.saveAndFlush(tenantId, deviceCredentials);
        } catch (Exception t) {
            ConstraintViolationException e = extractConstraintViolationException(t).orElse(null);
            if (e != null && e.getConstraintName() != null
                    && (e.getConstraintName().equalsIgnoreCase("device_credentials_id_unq_key") || e.getConstraintName().equalsIgnoreCase("device_credentials_device_id_unq_key"))) {
                throw new DataValidationException("Specified credentials are already registered!");
            } else {
                throw t;
            }
        }
    }

    @Override
    public void formatCredentials(DeviceCredentials deviceCredentials) {
        switch (deviceCredentials.getCredentialsType()) {
            case X509_CERTIFICATE:
                formatCertData(deviceCredentials);
                break;
            case MQTT_BASIC:
                formatSimpleMqttCredentials(deviceCredentials);
                break;
            case LWM2M_CREDENTIALS:
                formatAndValidateSimpleLwm2mCredentials(deviceCredentials);
                break;
        }
    }

    private void formatSimpleMqttCredentials(DeviceCredentials deviceCredentials) {
        BasicMqttCredentials mqttCredentials;
        try {
            mqttCredentials = JacksonUtil.fromString(deviceCredentials.getCredentialsValue(), BasicMqttCredentials.class);
            if (mqttCredentials == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new DeviceCredentialsValidationException("Invalid credentials body for simple mqtt credentials!");
        }

        if (StringUtils.isEmpty(mqttCredentials.getClientId()) && StringUtils.isEmpty(mqttCredentials.getUserName())) {
            throw new DeviceCredentialsValidationException("Both mqtt client id and user name are empty!");
        }
        if (StringUtils.isNotEmpty(mqttCredentials.getClientId()) && StringUtils.isNotEmpty(mqttCredentials.getPassword()) && StringUtils.isEmpty(mqttCredentials.getUserName())) {
            throw new DeviceCredentialsValidationException("Password cannot be specified along with client id");
        }

        if (StringUtils.isEmpty(mqttCredentials.getClientId())) {
            deviceCredentials.setCredentialsId(mqttCredentials.getUserName());
        } else if (StringUtils.isEmpty(mqttCredentials.getUserName())) {
            deviceCredentials.setCredentialsId(EncryptionUtil.getSha3Hash(mqttCredentials.getClientId()));
        } else {
            deviceCredentials.setCredentialsId(EncryptionUtil.getSha3Hash("|", mqttCredentials.getClientId(), mqttCredentials.getUserName()));
        }
        if (StringUtils.isNotEmpty(mqttCredentials.getPassword())) {
            mqttCredentials.setPassword(mqttCredentials.getPassword());
        }
        deviceCredentials.setCredentialsValue(JacksonUtil.toString(mqttCredentials));
    }

    private void formatCertData(DeviceCredentials deviceCredentials) {
        String cert = EncryptionUtil.certTrimNewLines(deviceCredentials.getCredentialsValue());
        String sha3Hash = EncryptionUtil.getSha3Hash(cert);
        deviceCredentials.setCredentialsId(sha3Hash);
        deviceCredentials.setCredentialsValue(cert);
    }

    private void formatAndValidateSimpleLwm2mCredentials(DeviceCredentials deviceCredentials) {
        LwM2MDeviceCredentials lwM2MCredentials;
        try {
            lwM2MCredentials = JacksonUtil.fromString(deviceCredentials.getCredentialsValue(), LwM2MDeviceCredentials.class);
            validateLwM2MDeviceCredentials(lwM2MCredentials);
        } catch (IllegalArgumentException e) {
            throw new DeviceCredentialsValidationException("Invalid credentials body for LwM2M credentials!");
        }

        String credentialsId = null;
        LwM2MClientCredential clientCredentials = lwM2MCredentials.getClient();
        switch (clientCredentials.getSecurityConfigClientMode()) {
            case NO_SEC:
            case RPK:
                deviceCredentials.setCredentialsValue(JacksonUtil.toString(lwM2MCredentials));
                credentialsId = clientCredentials.getEndpoint();
                break;
            case PSK:
                credentialsId = ((PSKClientCredential) clientCredentials).getIdentity();
                break;
            case X509:
                deviceCredentials.setCredentialsValue(JacksonUtil.toString(lwM2MCredentials));
                X509ClientCredential x509ClientConfig = (X509ClientCredential) clientCredentials;
                if ((StringUtils.isNotBlank(x509ClientConfig.getCert()))) {
                    String sha3Hash = EncryptionUtil.getSha3Hash(x509ClientConfig.getCert());
                    credentialsId = sha3Hash;
                } else {
                    credentialsId = x509ClientConfig.getEndpoint();
                }
                break;
        }
        if (credentialsId == null) {
            throw new DeviceCredentialsValidationException("Invalid credentials body for LwM2M credentials!");
        }
        deviceCredentials.setCredentialsId(credentialsId);
    }

    private void validateLwM2MDeviceCredentials(LwM2MDeviceCredentials lwM2MCredentials) {
        if (lwM2MCredentials == null) {
            throw new DeviceCredentialsValidationException("LwM2M credentials must be specified!");
        }

        LwM2MClientCredential clientCredentials = lwM2MCredentials.getClient();
        if (clientCredentials == null) {
            throw new DeviceCredentialsValidationException("LwM2M client credentials must be specified!");
        }
        validateLwM2MClientCredentials(clientCredentials);

        LwM2MBootstrapClientCredentials bootstrapCredentials = lwM2MCredentials.getBootstrap();
        if (bootstrapCredentials == null) {
            throw new DeviceCredentialsValidationException("LwM2M bootstrap credentials must be specified!");
        }

        LwM2MBootstrapClientCredential bootstrapServerCredentials = bootstrapCredentials.getBootstrapServer();
        if (bootstrapServerCredentials == null) {
            throw new DeviceCredentialsValidationException("LwM2M bootstrap server credentials must be specified!");
        }
        validateServerCredentials(bootstrapServerCredentials, "Bootstrap server");

        LwM2MBootstrapClientCredential lwm2MBootstrapClientCredential = bootstrapCredentials.getLwm2mServer();
        if (lwm2MBootstrapClientCredential == null) {
            throw new DeviceCredentialsValidationException("LwM2M lwm2m server credentials must be specified!");
        }
        validateServerCredentials(lwm2MBootstrapClientCredential, "LwM2M server");
    }

    private void validateLwM2MClientCredentials(LwM2MClientCredential clientCredentials) {
        if (StringUtils.isBlank(clientCredentials.getEndpoint())) {
            throw new DeviceCredentialsValidationException("LwM2M client endpoint must be specified!");
        }

        switch (clientCredentials.getSecurityConfigClientMode()) {
            case NO_SEC:
                break;
            case PSK:
                PSKClientCredential pskCredentials = (PSKClientCredential) clientCredentials;
                if (StringUtils.isBlank(pskCredentials.getIdentity())) {
                    throw new DeviceCredentialsValidationException("LwM2M client PSK identity must be specified and must be an utf8 string!");
                }
                // SecurityMode.NO_SEC.toString() == "NO_SEC";
                if (pskCredentials.getIdentity().equals(SecurityMode.NO_SEC.toString())) {
                    throw new DeviceCredentialsValidationException("The PSK ID of the LwM2M client must not be '" + SecurityMode.NO_SEC + "'!");
                }

                String pskKey = pskCredentials.getKey();
                if (StringUtils.isBlank(pskKey)) {
                    throw new DeviceCredentialsValidationException("LwM2M client PSK key must be specified!");
                }

                if (!pskKey.matches("-?[0-9a-fA-F]+")) {
                    throw new DeviceCredentialsValidationException("LwM2M client PSK key must be random sequence in hex encoding!");
                }

                if (pskKey.length()% 32 != 0 || pskKey.length() > 128) {
                    throw new DeviceCredentialsValidationException("LwM2M client PSK key length = " + pskKey.length() + ". Key must be HexDec format: 32, 64, 128 characters!");
                }

                break;
            case RPK:
                RPKClientCredential rpkCredentials = (RPKClientCredential) clientCredentials;
                if (StringUtils.isBlank(rpkCredentials.getKey())) {
                    throw new DeviceCredentialsValidationException("LwM2M client RPK key must be specified!");
                }

                try {
                    String pubkClient = EncryptionUtil.pubkTrimNewLines(rpkCredentials.getKey());
                    rpkCredentials.setKey(pubkClient);
                    SecurityUtil.publicKey.decode(rpkCredentials.getDecoded());
                } catch (Exception e) {
                    throw new DeviceCredentialsValidationException("LwM2M client RPK key must be in standard [RFC7250] and support only EC algorithm and then encoded to Base64 format!");
                }
                break;
            case X509:
                X509ClientCredential x509CCredentials = (X509ClientCredential) clientCredentials;
                if (StringUtils.isNotEmpty(x509CCredentials.getCert())) {
                    try {
                        String certClient = EncryptionUtil.certTrimNewLines(x509CCredentials.getCert());
                        x509CCredentials.setCert(certClient);
                        SecurityUtil.certificate.decode(x509CCredentials.getDecoded());
                    } catch (Exception e) {
                        throw new DeviceCredentialsValidationException("LwM2M client X509 certificate must be in DER-encoded X509v3 format and support only EC algorithm and then encoded to Base64 format!");
                    }
                }
                break;
        }
    }

    private void validateServerCredentials(LwM2MBootstrapClientCredential serverCredentials, String server) {
        switch (serverCredentials.getSecurityMode()) {
            case NO_SEC:
                break;
            case PSK:
                PSKBootstrapClientCredential pskCredentials = (PSKBootstrapClientCredential) serverCredentials;
                if (StringUtils.isBlank(pskCredentials.getClientPublicKeyOrId())) {
                    throw new DeviceCredentialsValidationException(server + " client PSK public key or id must be specified and must be an utf8 string!");
                }

                // SecurityMode.NO_SEC.toString() == "NO_SEC";
                if (pskCredentials.getClientPublicKeyOrId().equals(SecurityMode.NO_SEC.toString())) {
                    throw new DeviceCredentialsValidationException(server + " client PSK public key or id must not be '" + SecurityMode.NO_SEC + "'!");
                }

                String pskKey = pskCredentials.getClientSecretKey();
                if (StringUtils.isBlank(pskKey)) {
                    throw new DeviceCredentialsValidationException(server + " client PSK key must be specified!");
                }

                if (!pskKey.matches("-?[0-9a-fA-F]+")) {
                    throw new DeviceCredentialsValidationException(server + " client PSK key must be random sequence in hex encoding!");
                }

                if (pskKey.length() % 32 != 0 || pskKey.length() > 128) {
                    throw new DeviceCredentialsValidationException(server + " client PSK key length = " + pskKey.length() + ". Key must be HexDec format: 32, 64, 128 characters!");
                }
                break;
            case RPK:
                RPKBootstrapClientCredential rpkServerCredentials = (RPKBootstrapClientCredential) serverCredentials;
                if (StringUtils.isEmpty(rpkServerCredentials.getClientPublicKeyOrId())) {
                    throw new DeviceCredentialsValidationException(server + " client RPK public key or id must be specified!");
                }
                try {
                    String pubkRpkSever = EncryptionUtil.pubkTrimNewLines(rpkServerCredentials.getClientPublicKeyOrId());
                    rpkServerCredentials.setClientPublicKeyOrId(pubkRpkSever);
                    SecurityUtil.publicKey.decode(rpkServerCredentials.getDecodedClientPublicKeyOrId());
                } catch (Exception e) {
                    throw new DeviceCredentialsValidationException(server + " client RPK public key or id must be in standard [RFC7250 ] and then encoded to Base64 format!");
                }

                if (StringUtils.isEmpty(rpkServerCredentials.getClientSecretKey())) {
                    throw new DeviceCredentialsValidationException(server + " client RPK secret key must be specified!");
                }

                try {
                    String prikRpkSever = EncryptionUtil.prikTrimNewLines(rpkServerCredentials.getClientSecretKey());
                    rpkServerCredentials.setClientSecretKey(prikRpkSever);
                    SecurityUtil.privateKey.decode(rpkServerCredentials.getDecodedClientSecretKey());
                } catch (Exception e) {
                    throw new DeviceCredentialsValidationException(server + " client RPK secret key must be in PKCS#8 format (DER encoding, standard [RFC5958]) and then encoded to Base64 format!");
                }
                break;
            case X509:
                X509BootstrapClientCredential x509ServerCredentials = (X509BootstrapClientCredential) serverCredentials;
                if (StringUtils.isBlank(x509ServerCredentials.getClientPublicKeyOrId())) {
                    throw new DeviceCredentialsValidationException(server + " client X509 public key or id must be specified!");
                }

                try {
                    String certServer = EncryptionUtil.certTrimNewLines(x509ServerCredentials.getClientPublicKeyOrId());
                    x509ServerCredentials.setClientPublicKeyOrId(certServer);
                    SecurityUtil.certificate.decode(x509ServerCredentials.getDecodedClientPublicKeyOrId());
                } catch (Exception e) {
                    throw new DeviceCredentialsValidationException(server + " client X509 public key or id must be in DER-encoded X509v3 format  and support only EC algorithm and then encoded to Base64 format!");
                }
                if (StringUtils.isBlank(x509ServerCredentials.getClientSecretKey())) {
                    throw new DeviceCredentialsValidationException(server + " client X509 secret key must be specified!");
                }

                try {
                    String prikX509Sever = EncryptionUtil.prikTrimNewLines(x509ServerCredentials.getClientSecretKey());
                    x509ServerCredentials.setClientSecretKey(prikX509Sever);
                    SecurityUtil.privateKey.decode(x509ServerCredentials.getDecodedClientSecretKey());
                } catch (Exception e) {
                    throw new DeviceCredentialsValidationException(server + " client X509 secret key must be in PKCS#8 format (DER encoding, standard [RFC5958]) and then encoded to Base64 format!");
                }
                break;
        }
    }

    @Override
    @CacheEvict(cacheNames = DEVICE_CREDENTIALS_CACHE, key = "'deviceCredentials_' + #deviceCredentials.credentialsId")
    public void deleteDeviceCredentials(TenantId tenantId, DeviceCredentials deviceCredentials) {
        log.trace("Executing deleteDeviceCredentials [{}]", deviceCredentials);
        deviceCredentialsDao.removeById(tenantId, deviceCredentials.getUuidId());
    }

    private DataValidator<DeviceCredentials> credentialsValidator =
            new DataValidator<DeviceCredentials>() {

                @Override
                protected void validateCreate(TenantId tenantId, DeviceCredentials deviceCredentials) {
                    if (deviceCredentialsDao.findByDeviceId(tenantId, deviceCredentials.getDeviceId().getId()) != null) {
                        throw new DeviceCredentialsValidationException("Credentials for this device are already specified!");
                    }
                    if (deviceCredentialsDao.findByCredentialsId(tenantId, deviceCredentials.getCredentialsId()) != null) {
                        throw new DeviceCredentialsValidationException("Device credentials are already assigned to another device!");
                    }
                }

                @Override
                protected void validateUpdate(TenantId tenantId, DeviceCredentials deviceCredentials) {
                    if (deviceCredentialsDao.findById(tenantId, deviceCredentials.getUuidId()) == null) {
                        throw new DeviceCredentialsValidationException("Unable to update non-existent device credentials!");
                    }
                    DeviceCredentials existingCredentials = deviceCredentialsDao.findByCredentialsId(tenantId, deviceCredentials.getCredentialsId());
                    if (existingCredentials != null && !existingCredentials.getId().equals(deviceCredentials.getId())) {
                        throw new DeviceCredentialsValidationException("Device credentials are already assigned to another device!");
                    }
                }

                @Override
                protected void validateDataImpl(TenantId tenantId, DeviceCredentials deviceCredentials) {
                    if (deviceCredentials.getDeviceId() == null) {
                        throw new DeviceCredentialsValidationException("Device credentials should be assigned to device!");
                    }
                    if (deviceCredentials.getCredentialsType() == null) {
                        throw new DeviceCredentialsValidationException("Device credentials type should be specified!");
                    }
                    if (StringUtils.isEmpty(deviceCredentials.getCredentialsId())) {
                        throw new DeviceCredentialsValidationException("Device credentials id should be specified!");
                    }
                    Device device = deviceService.findDeviceById(tenantId, deviceCredentials.getDeviceId());
                    if (device == null) {
                        throw new DeviceCredentialsValidationException("Can't assign device credentials to non-existent device!");
                    }
                }
            };

}
