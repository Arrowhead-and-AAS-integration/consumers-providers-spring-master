package ai.aitia.demo.car_provider_with_publishing;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.aitia.arrowhead.application.library.ArrowheadService;
import ai.aitia.arrowhead.application.library.config.ApplicationInitListener;
import ai.aitia.arrowhead.application.library.util.ApplicationCommonConstants;
import eu.arrowhead.application.skeleton.provider.security.ProviderSecurityConfig;
import eu.arrowhead.application.skeleton.publisher.event.PresetEventType;
import eu.arrowhead.common.CommonConstants;
import eu.arrowhead.common.Utilities;
import eu.arrowhead.common.core.CoreSystem;
import eu.arrowhead.common.dto.shared.EventPublishRequestDTO;
import eu.arrowhead.common.dto.shared.ServiceRegistryRequestDTO;
import eu.arrowhead.common.dto.shared.ServiceSecurityType;
import eu.arrowhead.common.dto.shared.SystemRequestDTO;
import eu.arrowhead.common.exception.ArrowheadException;

@Component
public class CarProviderWithPublishingApplicationInitListener extends ApplicationInitListener {
	
	//=================================================================================================
	// members
	
	@Autowired
	private ArrowheadService arrowheadService;
	
	@Autowired
	private ProviderSecurityConfig providerSecurityConfig;
	
	@Value(ApplicationCommonConstants.$TOKEN_SECURITY_FILTER_ENABLED_WD)
	private boolean tokenSecurityFilterEnabled;
	
	@Value(CommonConstants.$SERVER_SSL_ENABLED_WD)
	private boolean sslEnabled;
	
	@Value(ApplicationCommonConstants.$APPLICATION_SYSTEM_NAME)
	private String mySystemName;
	
	@Value(ApplicationCommonConstants.$APPLICATION_SERVER_ADDRESS_WD)
	private String mySystemAddress;
	
	@Value(ApplicationCommonConstants.$APPLICATION_SERVER_PORT_WD)
	private int mySystemPort;
	
	private final Logger logger = LogManager.getLogger(CarProviderWithPublishingApplicationInitListener.class);
	
	//=================================================================================================
	// methods

	//-------------------------------------------------------------------------------------------------
	@Override
	protected void customInit(final ContextRefreshedEvent event) {
		checkConfiguration();
		
		//Checking the availability of necessary core systems
		checkCoreSystemReachability(CoreSystem.SERVICEREGISTRY);
		if (sslEnabled && tokenSecurityFilterEnabled) {
			checkCoreSystemReachability(CoreSystem.AUTHORIZATION);			

			//Initialize Arrowhead Context
			arrowheadService.updateCoreServiceURIs(CoreSystem.AUTHORIZATION);
			
			setTokenSecurityFilter();
		} else {
			logger.info("TokenSecurityFilter in not active");
		}		
		
		//Register services into ServiceRegistry
		final ServiceRegistryRequestDTO createCarServiceRequest = createServiceRegistryRequest(CarProviderWithPublishingConstants.CREATE_CAR_SERVICE_DEFINITION, CarProviderWithPublishingConstants.CAR_URI, HttpMethod.POST);		
		arrowheadService.forceRegisterServiceToServiceRegistry(createCarServiceRequest);
		
		final ServiceRegistryRequestDTO getCarServiceRequest = createServiceRegistryRequest(CarProviderWithPublishingConstants.GET_CAR_SERVICE_DEFINITION,  CarProviderWithPublishingConstants.CAR_URI, HttpMethod.GET);
		getCarServiceRequest.getMetadata().put(CarProviderWithPublishingConstants.REQUEST_PARAM_KEY_BRAND, CarProviderWithPublishingConstants.REQUEST_PARAM_BRAND);
		getCarServiceRequest.getMetadata().put(CarProviderWithPublishingConstants.REQUEST_PARAM_KEY_COLOR, CarProviderWithPublishingConstants.REQUEST_PARAM_COLOR);
		arrowheadService.forceRegisterServiceToServiceRegistry(getCarServiceRequest);
		
		if (arrowheadService.echoCoreSystem(CoreSystem.EVENTHANDLER)) {
			arrowheadService.updateCoreServiceURIs(CoreSystem.EVENTHANDLER);	
		}

		
		// Start a new thread to run publishDestroyedEvent() every 10 seconds
		Timer timer = new Timer(true);
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				publishDestroyedEvent();
				logger.info("Sending event to event handler");
			}
		}, 0, 5000);
	}
	
	//-------------------------------------------------------------------------------------------------
	@Override
	public void customDestroy() {
		//Unregister service
		publishDestroyedEvent();
		arrowheadService.unregisterServiceFromServiceRegistry(CarProviderWithPublishingConstants.CREATE_CAR_SERVICE_DEFINITION, CarProviderWithPublishingConstants.CAR_URI);
		arrowheadService.unregisterServiceFromServiceRegistry(CarProviderWithPublishingConstants.GET_CAR_SERVICE_DEFINITION, CarProviderWithPublishingConstants.CAR_URI);
	}
	
	//=================================================================================================
	// assistant methods
	
	//-------------------------------------------------------------------------------------------------
	private void checkConfiguration() {
		if (!sslEnabled && tokenSecurityFilterEnabled) {			 
			logger.warn("Contradictory configuration:");
			logger.warn("token.security.filter.enabled=true while server.ssl.enabled=false");
		}
	}

	//-------------------------------------------------------------------------------------------------
	private void publishDestroyedEvent() {
		final String eventType = PresetEventType.PUBLISHER_DESTROYED.getEventTypeName();
		
		final SystemRequestDTO source = new SystemRequestDTO();
		source.setSystemName(mySystemName);
		source.setAddress(mySystemAddress);
		source.setPort(mySystemPort);
		if (sslEnabled) {
			source.setAuthenticationInfo(Base64.getEncoder().encodeToString( arrowheadService.getMyPublicKey().getEncoded()));
		}

		// Make a GET request to the endpoint
		RestTemplate restTemplate = new RestTemplate();
		String url = "http://localhost:8082/registry/api/v1/registry/ProximitySensorID/submodels";
		ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

		String proximityValue = "0";

		logger.info(response.getBody());

		// Parse the JSON response to get the value of the submodel with idShort "ProximityData"
		try {
			ObjectMapper objectMapper = new ObjectMapper();
			JsonNode root = objectMapper.readTree(response.getBody());
			if (root.isArray() && root.size() > 0) {
				for (JsonNode submodel : root) {
					if ("ProximityData".equalsIgnoreCase(submodel.path("idShort").asText())) {
						proximityValue = submodel.path("value").asText();
						logger.info("Submodel ProximityData value: " + proximityValue);
						break;
					}
				}
			} else {
				logger.warn("No submodel found in the response");
			}
		} catch (Exception e) {
			logger.error("Error parsing JSON response", e);
		}

		final Map<String,String> metadata = null;

		final String payload = String.valueOf(proximityValue);
		final String timeStamp = Utilities.convertZonedDateTimeToUTCString( ZonedDateTime.now() );
		
		final EventPublishRequestDTO publishRequestDTO = new EventPublishRequestDTO(
				eventType, 
				source,
				metadata, 
				payload, 
				timeStamp);
		
		arrowheadService.publishToEventHandler(publishRequestDTO);
	}

	//-------------------------------------------------------------------------------------------------
	private void setTokenSecurityFilter() {
		final PublicKey authorizationPublicKey = arrowheadService.queryAuthorizationPublicKey();
		if (authorizationPublicKey == null) {
			throw new ArrowheadException("Authorization public key is null");
		}
		
		KeyStore keystore;
		try {
			keystore = KeyStore.getInstance(sslProperties.getKeyStoreType());
			keystore.load(sslProperties.getKeyStore().getInputStream(), sslProperties.getKeyStorePassword().toCharArray());
		} catch (final KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException ex) {
			throw new ArrowheadException(ex.getMessage());
		}			
		final PrivateKey providerPrivateKey = Utilities.getPrivateKey(keystore, sslProperties.getKeyPassword());
		
		providerSecurityConfig.getTokenSecurityFilter().setAuthorizationPublicKey(authorizationPublicKey);
		providerSecurityConfig.getTokenSecurityFilter().setMyPrivateKey(providerPrivateKey);
	}
	
	//-------------------------------------------------------------------------------------------------
	private ServiceRegistryRequestDTO createServiceRegistryRequest(final String serviceDefinition, final String serviceUri, final HttpMethod httpMethod) {
		final ServiceRegistryRequestDTO serviceRegistryRequest = new ServiceRegistryRequestDTO();
		serviceRegistryRequest.setServiceDefinition(serviceDefinition);
		final SystemRequestDTO systemRequest = new SystemRequestDTO();
		systemRequest.setSystemName(mySystemName);
		systemRequest.setAddress(mySystemAddress);
		systemRequest.setPort(mySystemPort);		

		if (sslEnabled && tokenSecurityFilterEnabled) {
			systemRequest.setAuthenticationInfo(Base64.getEncoder().encodeToString(arrowheadService.getMyPublicKey().getEncoded()));
			serviceRegistryRequest.setSecure(ServiceSecurityType.TOKEN.name());
			serviceRegistryRequest.setInterfaces(List.of(CarProviderWithPublishingConstants.INTERFACE_SECURE));
		} else if (sslEnabled) {
			systemRequest.setAuthenticationInfo(Base64.getEncoder().encodeToString(arrowheadService.getMyPublicKey().getEncoded()));
			serviceRegistryRequest.setSecure(ServiceSecurityType.CERTIFICATE.name());
			serviceRegistryRequest.setInterfaces(List.of(CarProviderWithPublishingConstants.INTERFACE_SECURE));
		} else {
			serviceRegistryRequest.setSecure(ServiceSecurityType.NOT_SECURE.name());
			serviceRegistryRequest.setInterfaces(List.of(CarProviderWithPublishingConstants.INTERFACE_INSECURE));
		}
		serviceRegistryRequest.setProviderSystem(systemRequest);
		serviceRegistryRequest.setServiceUri(serviceUri);
		serviceRegistryRequest.setMetadata(new HashMap<>());
		serviceRegistryRequest.getMetadata().put(CarProviderWithPublishingConstants.HTTP_METHOD, httpMethod.name());
		return serviceRegistryRequest;
	}
}