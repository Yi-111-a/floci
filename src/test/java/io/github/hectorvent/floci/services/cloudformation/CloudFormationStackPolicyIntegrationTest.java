package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.github.hectorvent.floci.testing.CfnStackWaits.awaitStatus;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CloudFormationStackPolicyIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources":{"Q":{"Type":"AWS::SQS::Queue","Properties":{}}}}
            """;

    private static final String POLICY = """
            {"Statement":[{"Effect":"Allow","Action":"Update:*","Principal":"*","Resource":"*"}]}
            """;

    private static String policyName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void setStackPolicyStoresAndGetStackPolicyReturnsItVerbatim() {
        String stackName = policyName("cfn-policy-roundtrip-");
        cfn("CreateStack", stackName).formParam("TemplateBody", TEMPLATE)
                .when().post("/").then().statusCode(200);
        awaitStatus(stackName, "CREATE_COMPLETE");

        cfn("SetStackPolicy", stackName).formParam("StackPolicyBody", POLICY)
                .when().post("/").then().statusCode(200);

        cfn("GetStackPolicy", stackName).when().post("/")
                .then().statusCode(200)
                .body(containsString("Update:*"))
                .body(containsString("Statement"));
        CfnStackWaits.deleteStack(stackName);
    }

    @Test
    void getStackPolicyForAStackWithoutAPolicyIsAnEmptyResult() {
        String stackName = policyName("cfn-policy-empty-");
        cfn("CreateStack", stackName).formParam("TemplateBody", TEMPLATE)
                .when().post("/").then().statusCode(200);
        awaitStatus(stackName, "CREATE_COMPLETE");

        cfn("GetStackPolicy", stackName).when().post("/")
                .then().statusCode(200)
                .body(not(containsString("StackPolicyBody")));
        CfnStackWaits.deleteStack(stackName);
    }

    @Test
    void setStackPolicyRefusesAMissingStack() {
        String stackName = policyName("cfn-policy-missing-");
        cfn("SetStackPolicy", stackName).formParam("StackPolicyBody", POLICY)
                .when().post("/").then().statusCode(400)
                .body(containsString("Stack: " + stackName + " does not exist"));
    }

    @Test
    void setStackPolicyRequiresExactlyOnePolicySource() {
        String stackName = policyName("cfn-policy-source-");
        cfn("SetStackPolicy", stackName)
                .when().post("/").then().statusCode(400)
                .body(containsString("Ether StackPolicyURL or StackPolicyBody must be specified."));
        cfn("SetStackPolicy", stackName)
                .formParam("StackPolicyBody", POLICY)
                .formParam("StackPolicyURL", "https://s3.us-east-1.amazonaws.com/bucket/policy.json")
                .when().post("/").then().statusCode(400)
                .body(containsString("You cannot specify both StackPolicyURL and StackPolicyBody"));
    }

    @Test
    void setStackPolicyRefusesABodyThatIsNotAValidStackPolicy() {
        String stackName = policyName("cfn-policy-invalid-");
        cfn("CreateStack", stackName).formParam("TemplateBody", TEMPLATE)
                .when().post("/").then().statusCode(200);
        awaitStatus(stackName, "CREATE_COMPLETE");

        cfn("SetStackPolicy", stackName).formParam("StackPolicyBody", "persist")
                .when().post("/").then().statusCode(400)
                .body(containsString("Error validating stack policy: Invalid stack policy"));
        cfn("SetStackPolicy", stackName)
                .formParam("StackPolicyBody", "{\"Statement\":[{\"Effect\":\"Maybe\",\"Action\":\"Update:*\"}]}")
                .when().post("/").then().statusCode(400)
                .body(containsString("Error validating stack policy: Invalid stack policy"));
        CfnStackWaits.deleteStack(stackName);
    }

    @Test
    void createStackStoresAnAcceptedStackPolicyWhileTheStackIsStillArriving() {
        String stackName = policyName("cfn-policy-create-");
        cfn("CreateStack", stackName)
                .formParam("TemplateBody", TEMPLATE)
                .formParam("StackPolicyBody", POLICY)
                .when().post("/").then().statusCode(200);

        cfn("GetStackPolicy", stackName).when().post("/")
                .then().statusCode(200)
                .body(containsString("Update:*"));
        awaitStatus(stackName, "CREATE_COMPLETE");
        cfn("GetStackPolicy", stackName).when().post("/")
                .then().statusCode(200)
                .body(containsString("Update:*"));
        CfnStackWaits.deleteStack(stackName);
    }

    @Test
    void createStackRefusesAnInvalidStackPolicy() {
        String stackName = policyName("cfn-policy-create-rejected-");
        cfn("CreateStack", stackName)
                .formParam("TemplateBody", TEMPLATE)
                .formParam("StackPolicyBody", "persist")
                .when().post("/").then().statusCode(400)
                .body(containsString(
                        "Error validating stack policy to be applied after stack creation: Invalid stack policy"));
        cfn("GetStackPolicy", stackName).when().post("/")
                .then().statusCode(400)
                .body(containsString("Stack: " + stackName + " does not exist"));
    }

    @Test
    void updateStackRefusesAnInvalidStackPolicy() {
        String stackName = policyName("cfn-policy-update-rejected-");
        cfn("CreateStack", stackName).formParam("TemplateBody", TEMPLATE)
                .when().post("/").then().statusCode(200);
        awaitStatus(stackName, "CREATE_COMPLETE");

        cfn("UpdateStack", stackName)
                .formParam("UsePreviousTemplate", "true")
                .formParam("StackPolicyBody", "persist")
                .when().post("/").then().statusCode(400)
                .body(containsString(
                        "Error validating stack policy to be applied after stack update: Invalid stack policy"));
        CfnStackWaits.deleteStack(stackName);
    }
}
