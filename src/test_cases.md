# Broadcast Service - Conceptual Test Cases

## Introduction
This document outlines conceptual test scenarios for the eRoaming Broadcast Service. The service is responsible for broadcasting start-charging requests to all connected partners and returning the first successful response within a 5-second timeout.

## Test Scenarios

### 1. Happy Path Scenarios

#### TC-001: Immediate Success Response
**Description**: A partner recognizes the UID and responds quickly with success
- **Preconditions**:
    - Partner "charge-network-b" owns UID "P2-12345"
    - Partner responds within 1 second
- **Test Steps**:
    1. Send broadcast request with UID "P2-12345"
    2. Monitor response timing and content
- **Expected Results**:
    - Response received in < 2 seconds
    - `success: true`
    - `respondingPartner: "charge-network-b"`
    - Early termination occurred (other requests cancelled)

#### TC-002: Multiple Partners Respond Successfully
**Description**: Multiple partners could own the UID, but system stops at first success
- **Preconditions**:
    - Partner A responds at 1 second with success
    - Partner B responds at 2 seconds with success
- **Test Steps**:
    1. Send broadcast request
    2. Monitor which partner response is accepted
- **Expected Results**:
    - System uses Partner A's response (first to succeed)
    - Partner B's request is cancelled
    - Total time ≈ 1 second

### 2. Error Scenarios

#### TC-003: No Partner Owns UID
**Description**: No partner recognizes the provided UID
- **Preconditions**: UID "UNKNOWN-99999" not owned by any partner
- **Test Steps**:
    1. Send broadcast request with unknown UID
    2. Wait for all partner responses
- **Expected Results**:
    - `success: false`
    - Appropriate error message in response
    - All partner responses collected in `partnerResponses`
    - All responses indicate failure

#### TC-004: All Partners Return Errors
**Description**: Partners respond with various error conditions
- **Preconditions**:
    - Partner A: HTTP 500 error
    - Partner B: HTTP 404 error
    - Partner C: Invalid JSON response
- **Test Steps**:
    1. Send broadcast request
    2. Monitor error handling
- **Expected Results**:
    - `success: false`
    - All errors gracefully handled without system crash
    - Error details captured in partner responses

### 3. Timeout Scenarios

#### TC-005: Partner Responds After Timeout
**Description**: A partner responds slowly (after 5-second timeout)
- **Preconditions**: Partner "ev-provider-a" responds in 6 seconds
- **Test Steps**:
    1. Send broadcast request
    2. Monitor timeout behavior
- **Expected Results**:
    - Broadcast completes in exactly 5 seconds
    - Slow partner marked as timeout in responses
    - `success: false` (unless another partner succeeded)

#### TC-006: All Partners Timeout
**Description**: All partners respond slower than 5 seconds
- **Preconditions**: All partners configured with 6+ second response times
- **Test Steps**:
    1. Send broadcast request
    2. Monitor global timeout
- **Expected Results**:
    - System returns after 5 seconds
    - `success: false`
    - All partners marked as timeout
    - Appropriate timeout message

### 4. Performance and Scalability

#### TC-007: High Concurrent Load
**Description**: Multiple simultaneous broadcast requests
- **Preconditions**: 50 active partners, 10 concurrent broadcasts
- **Test Steps**:
    1. Send 10 simultaneous broadcast requests
    2. Monitor system resource usage
- **Expected Results**:
    - All requests complete within 5 seconds
    - No thread pool exhaustion
    - Memory usage remains stable
    - Each request gets isolated, correct response

#### TC-008: Large Number of Partners
**Description**: System with 100+ configured partners
- **Preconditions**: 150 partners in database (mix of active/inactive)
- **Test Steps**:
    1. Send broadcast request
    2. Monitor request distribution and performance
- **Expected Results**:
    - Only active partners receive requests
    - Cache efficiently serves partner configurations
    - Response time not significantly impacted by partner count

### 5. Configuration Variability

#### TC-009: Different Success Patterns
**Description**: Partners use different success indicators
- **Preconditions**:
    - Partner A: success pattern = "success"
    - Partner B: success pattern = "approved"
    - Partner C: success pattern = "ok,active"
- **Test Steps**:
    1. Send broadcast where each partner returns their success pattern
    2. Verify correct success detection
- **Expected Results**:
    - Each partner's success correctly identified
    - Multiple patterns (comma-separated) work correctly
    - Case-insensitive matching

#### TC-010: Different Response Field Paths
**Description**: Partners have nested response structures
- **Preconditions**:
    - Partner A: response path = "status"
    - Partner B: response path = "result.status"
    - Partner C: response path = "data.outcome.status"
- **Test Steps**:
    1. Send broadcast with nested response structures
    2. Verify field extraction works correctly
- **Expected Results**:
    - All nested fields correctly extracted
    - Missing fields handled gracefully
    - Extraction errors don't crash the system

#### TC-011: Different Authentication Methods
**Description**: Partners use various authentication schemes
- **Preconditions**:
    - Partner A: API Key authentication
    - Partner B: Bearer token
    - Partner C: Basic auth
    - Partner D: No authentication
- **Test Steps**:
    1. Send broadcast request
    2. Monitor authentication headers in outbound requests
- **Expected Results**:
    - Correct headers set for each auth type
    - Basic auth properly encoded
    - Bearer tokens correctly formatted
    - No auth partners don't get auth headers

#### TC-012: Different Request Formats
**Description**: Partners accept different data formats
- **Preconditions**:
    - Partner A: JSON format
    - Partner B: XML format
    - Partner C: Form-data format
- **Test Steps**:
    1. Send broadcast request
    2. Monitor content-type headers and request bodies
- **Expected Results**:
    - Correct content-type headers for each format
    - JSON bodies properly structured
    - XML bodies correctly formatted
    - Form-data properly encoded

### 6. Edge Cases

#### TC-013: Empty Partner List
**Description**: No active partners configured
- **Preconditions**: Database has no active partners
- **Test Steps**:
    1. Send broadcast request
- **Expected Results**:
    - Immediate response with `success: false`
    - Clear error message about no active partners
    - No HTTP requests attempted

#### TC-014: Malformed Partner Configuration
**Description**: Partner has invalid configuration (missing URL, etc.)
- **Preconditions**: Partner configuration missing required fields
- **Test Steps**:
    1. Send broadcast request
    2. Monitor error handling
- **Expected Results**:
    - Invalid partners skipped gracefully
    - Error logged but system continues
    - Other valid partners still processed

#### TC-015: Network Issues
**Description**: Partners are unreachable or return network errors
- **Preconditions**:
    - Partner A: Connection refused
    - Partner B: DNS resolution failed
    - Partner C: SSL certificate issues
- **Test Steps**:
    1. Send broadcast request
    2. Monitor network error handling
- **Expected Results**:
    - All network errors caught and handled
    - Partners marked with appropriate error status
    - System doesn't crash on network issues

### 7. Cache and Configuration Management

#### TC-016: Dynamic Partner Configuration Changes
**Description**: Partner configurations updated during runtime
- **Preconditions**: Partner disabled via database update
- **Test Steps**:
    1. Disable a partner in database
    2. Send broadcast request
    3. Verify cache refresh behavior
- **Expected Results**:
    - Disabled partner not included in broadcast
    - Cache refresh mechanism works correctly
    - No stale configuration used

#### TC-017: Cache Performance
**Description**: Verify caching improves performance
- **Preconditions**: System with cached partner configurations
- **Test Steps**:
    1. Send multiple sequential broadcast requests
    2. Monitor database query frequency
- **Expected Results**:
    - Database queried only on cache miss/refresh
    - Subsequent requests use cached configurations
    - Significant performance improvement

## Test Data Requirements

### Sample UIDs:
- `P1-12345` - Owned by EV Provider A
- `P2-67890` - Owned by Charge Network B
- `P3-11111` - Owned by Power Solutions C
- `UNKNOWN-999` - Not owned by any partner

### Mock Partner Responses:
```json
// Standard success
{"status": "success", "message": "Charging started"}

// Nested success  
{"result": {"status": "approved", "message": "Session initiated"}}

// Error response
{"status": "error", "message": "User not found"}