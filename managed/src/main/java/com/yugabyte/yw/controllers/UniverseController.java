// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.yugabyte.yw.cloud.AWSConstants;
import com.yugabyte.yw.cloud.AWSCostUtil;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.tasks.DestroyUniverse;
import com.yugabyte.yw.commissioner.tasks.params.UniverseDefinitionTaskParams;
import com.yugabyte.yw.common.ApiHelper;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.forms.UniverseFormData;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.CloudSpecificInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementAZ;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementCloud;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementRegion;
import com.yugabyte.yw.models.helpers.UserIntent;

import play.data.Form;
import play.data.FormFactory;
import play.libs.Json;
import play.mvc.Result;
import play.mvc.Results;

public class UniverseController extends AuthenticatedController {
  public static final Logger LOG = LoggerFactory.getLogger(UniverseController.class);

  @Inject
  FormFactory formFactory;

  @Inject
  ApiHelper apiHelper;

  @Inject
  Commissioner commissioner;

  // This is the maximum number of subnets that the masters can be placed across, and need to be an
  // odd number for consensus to work.
  public static final int maxMasterSubnets = 3;

  /**
   * API that queues a task to create a new universe. This does not wait for the creation.
   * @return result of the universe create operation.
   */
  public Result create(UUID customerUUID) {
    try {
      LOG.info("Create for {}.", customerUUID);
      // Get the user submitted form data.
      Form<UniverseFormData> formData =
              formFactory.form(UniverseFormData.class).bindFromRequest();

      // Check for any form errors.
      if (formData.hasErrors()) {
        return ApiResponse.error(BAD_REQUEST, formData.errorsAsJson());
      }

      // Verify the customer with this universe is present.
      Customer customer = Customer.get(customerUUID);
      if (customer == null) {
        return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
      }

      // Create a new universe. This makes sure that a universe of this name does not already exist
      // for this customer id.
      Universe universe = Universe.create(formData.get().universeName, customer.getCustomerId());
      LOG.info("Created universe {} : {}.", universe.universeUUID, universe.name);

      // Add an entry for the universe into the customer table.
      customer.addUniverseUUID(universe.universeUUID);
      customer.save();

      LOG.info("Added universe {} : {} for customer [{}].",
              universe.universeUUID, universe.name, customer.getCustomerId());

      UniverseDefinitionTaskParams taskParams =
              getTaskParams(formData, universe, customer.getCustomerId());

      // Submit the task to create the universe.
      UUID taskUUID = commissioner.submit(TaskInfo.Type.CreateUniverse, taskParams);
      LOG.info("Submitted create universe for {}:{}, task uuid = {}.",
              universe.universeUUID, universe.name, taskUUID);

      // Add this task uuid to the user universe.
      CustomerTask.create(customer,
              universe,
              taskUUID,
              CustomerTask.TargetType.Universe,
              CustomerTask.TaskType.Create,
              universe.name);
      LOG.info("Saved task uuid " + taskUUID + " in customer tasks table for universe " +
              universe.universeUUID + ":" + universe.name);

      ObjectNode resultNode = (ObjectNode)universe.toJson();
      resultNode.put("taskUUID", taskUUID.toString());
      return Results.status(OK, resultNode);
    } catch (Throwable t) {
      LOG.error("Error creating universe", t);
      return ApiResponse.error(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  /**
   * API that queues a task to update/edit a universe of a given customer.
   * This does not wait for the completion.
   *
   * @return result of the universe update operation.
   */
  public Result update(UUID customerUUID, UUID universeUUID) {
    try {
      LOG.info("Update {} for {}.", customerUUID, universeUUID);
      // Get the user submitted form data.
      Form<UniverseFormData> formData =
              formFactory.form(UniverseFormData.class).bindFromRequest();

      // Check for any form errors.
      if (formData.hasErrors()) {
        return ApiResponse.error(BAD_REQUEST, formData.errorsAsJson());
      }

      // Verify the customer with this universe is present.
      Customer customer = Customer.get(customerUUID);
      if (customer == null) {
        return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
      }

      // Get the universe. This makes sure that a universe of this name does exist
      // for this customer id.
      Universe universe = Universe.get(universeUUID);
      LOG.info("Found universe {} : name={} at version={}.",
               universe.universeUUID, universe.name, universe.version);

      UniverseDefinitionTaskParams taskParams =
              getTaskParams(formData, universe, customer.getCustomerId());

      UUID taskUUID = commissioner.submit(TaskInfo.Type.EditUniverse, taskParams);
      LOG.info("Submitted edit universe for {} : {}, task uuid = {}.",
              universe.universeUUID, universe.name, taskUUID);

      // Add this task uuid to the user universe.
      CustomerTask.create(customer,
              universe,
              taskUUID,
              CustomerTask.TargetType.Universe,
              CustomerTask.TaskType.Update,
              universe.name);
      LOG.info("Saved task uuid {} in customer tasks table for universe {} : {}.", taskUUID,
              universe.universeUUID, universe.name);
      ObjectNode resultNode = (ObjectNode)universe.toJson();
      resultNode.put("taskUUID", taskUUID.toString());
      return Results.status(OK, resultNode);
    } catch (Throwable t) {
      LOG.error("Error updating universe", t);
      return ApiResponse.error(INTERNAL_SERVER_ERROR, t.getMessage());
    }
  }

  /**
   * List the universes for a given customer.
   *
   * @return
   */
  public Result list(UUID customerUUID) {
    // Verify the customer is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    ArrayNode universes = Json.newArray();
    // TODO: Restrict the list api json payload, possibly to only include UUID, Name etc
    for (Universe universe: customer.getUniverses()) {
      universes.add(universe.toJson());
    }
    return ApiResponse.success(universes);
  }

  public Result index(UUID customerUUID, UUID universeUUID) {
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    try {
      Universe universe = Universe.get(universeUUID);
      return Results.status(OK, universe.toJson());
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Universe UUID: " + universeUUID);
    }
  }

  public Result destroy(UUID customerUUID, UUID universeUUID) {
    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }

    Universe universe;
    // Make sure the universe exists, this method will throw an exception if it does not.
    try {
      universe = Universe.get(universeUUID);
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "No universe found with UUID: " + universeUUID);
    }

    // Create the Commissioner task to destroy the universe.
    DestroyUniverse.Params taskParams = new DestroyUniverse.Params();
    taskParams.universeUUID = universeUUID;

    // Submit the task to destroy the universe.
    UUID taskUUID = commissioner.submit(TaskInfo.Type.DestroyUniverse, taskParams);
    LOG.info("Submitted destroy universe for " + universeUUID + ", task uuid = " + taskUUID);

    // Add this task uuid to the user universe.
    CustomerTask.create(customer,
            universe,
            taskUUID,
            CustomerTask.TargetType.Universe,
            CustomerTask.TaskType.Delete,
            universe.name);

    // Remove the entry for the universe from the customer table.
    customer.removeUniverseUUID(universeUUID);
    customer.save();
    LOG.info("Dropped universe " + universeUUID + " for customer [" + customer.name + "]");

    ObjectNode response = Json.newObject();
    response.put("taskUUID", taskUUID.toString());
    return ApiResponse.success(response);
  }

  public Result universeCost(UUID customerUUID, UUID universeUUID) {
    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }

    Universe universe;
    // Make sure the universe exists, this method will throw an exception if it does not.
    try {
      universe = Universe.get(universeUUID);
    }
    catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "No universe found with UUID: " + universeUUID);
    }
    try {
      ObjectNode universeCost = getUniverseCostUtil(universe);
      return ApiResponse.success(universeCost);
    }
    catch (Exception e) {
      return ApiResponse.error(INTERNAL_SERVER_ERROR,
                               "Error getting cost for customer " + customerUUID);
    }
  }

  public Result universeListCost(UUID customerUUID) {
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }
    ArrayNode response = Json.newArray();
    Set<Universe> universeSet = null;
    try {
      universeSet = customer.getUniverses();
    } catch (RuntimeException e) {
      return ApiResponse.error(BAD_REQUEST, "No universe found for customer with ID: " + customerUUID);
    }
    try {
      for (Universe universe : universeSet) {
        response.add(getUniverseCostUtil(universe));
      }
    } catch (Exception e) {
      return ApiResponse.error(INTERNAL_SERVER_ERROR,
                               "Error getting cost for customer " + customerUUID);
    }
    return ApiResponse.success(response);
  }

  /**
   * Configures the set of nodes to be created.
   *
   * @param nodePrefix node name prefix.
   * @param startIndex index to used for node naming.
   * @param numNodes   number of nodes desired.
   * @param numMasters number of masters among these nodes.
   * @param placementInfo desired placement info.
   *
   * @return set of node details with their placement info filled in.
   */
  private Set<NodeDetails> configureNewNodes(String nodePrefix,
                                             int startIndex,
                                             int numNodes,
                                             int numMasters,
                                             PlacementInfo placementInfo) {
    Set<NodeDetails> newNodesSet = new HashSet<NodeDetails>();
    Map<String, NodeDetails> newNodesMap = new HashMap<String, NodeDetails>();

    // Create the names and known properties of all the cluster nodes.
    int cloudIdx = 0;
    int regionIdx = 0;
    int azIdx = 0;
    for (int nodeIdx = startIndex; nodeIdx < startIndex + numNodes; nodeIdx++) {
      NodeDetails nodeDetails = new NodeDetails();
      // Create a temporary node name. These are fixed once the operation is actually run.
      nodeDetails.nodeName = nodePrefix + "-fake-n" + nodeIdx;
      // Set the cloud.
      PlacementCloud placementCloud = placementInfo.cloudList.get(cloudIdx);
      nodeDetails.cloudInfo = new CloudSpecificInfo();
      nodeDetails.cloudInfo.cloud = placementCloud.name;
      // Set the region.
      PlacementRegion placementRegion = placementCloud.regionList.get(regionIdx);
      nodeDetails.cloudInfo.region = placementRegion.code;
      // Set the AZ and the subnet.
      PlacementAZ placementAZ = placementRegion.azList.get(azIdx);
      nodeDetails.azUuid = placementAZ.uuid;
      nodeDetails.cloudInfo.az = placementAZ.name;
      nodeDetails.cloudInfo.subnet_id = placementAZ.subnet;
      // Set the tablet server role to true.
      nodeDetails.isTserver = true;
      // Set the node id.
      nodeDetails.nodeIdx = nodeIdx;
      // We are ready to add this node.
      nodeDetails.state = NodeDetails.NodeState.ToBeAdded; 
      // Add the node to the set of nodes.
      newNodesSet.add(nodeDetails);
      newNodesMap.put(nodeDetails.nodeName, nodeDetails);
      LOG.debug("Placed new node {} at cloud:{}, region:{}, az:{}.",
                nodeDetails.toString(), cloudIdx, regionIdx, azIdx);

      // Advance to the next az/region/cloud combo.
      azIdx = (azIdx + 1) % placementRegion.azList.size();
      regionIdx = (regionIdx + (azIdx == 0 ? 1 : 0)) % placementCloud.regionList.size();
      cloudIdx = (cloudIdx + (azIdx == 0 && regionIdx == 0 ? 1 : 0)) %
          placementInfo.cloudList.size();
    }

    // Select the masters for this cluster based on subnets.
    setMasters(newNodesMap, numMasters);

    return newNodesSet;
  }

  /**
   * Given a set of nodes and the number of masters, selects the masters and marks them as such.
   *
   * @param nodesMap   : a map of node name to NodeDetails
   * @param numMasters : the number of masters to choose
   * @return nothing
   */
  private static void setMasters(Map<String, NodeDetails> nodesMap, int numMasters) {
    // Group the cluster nodes by subnets.
    Map<String, TreeSet<String>> subnetsToNodenameMap = new HashMap<String, TreeSet<String>>();
    for (Entry<String, NodeDetails> entry : nodesMap.entrySet()) {
      String subnet = entry.getValue().cloudInfo.subnet_id;
      if (!subnetsToNodenameMap.containsKey(subnet)) {
        subnetsToNodenameMap.put(subnet, new TreeSet<String>());
      }
      TreeSet<String> nodeSet = subnetsToNodenameMap.get(subnet);
      // Add the node name into the node set.
      nodeSet.add(entry.getKey());
    }
    LOG.info("Subnet map has {}, nodesMap has {}, need {} masters.",
             subnetsToNodenameMap.size(), nodesMap.size(), numMasters);
    // Choose the masters such that we have one master per subnet if there are enough subnets.
    int numMastersChosen = 0;
    if (subnetsToNodenameMap.size() >= maxMasterSubnets) {
      for (Entry<String, TreeSet<String>> entry : subnetsToNodenameMap.entrySet()) {
        // Get one node from each subnet.
        String nodeName = entry.getValue().first();
        NodeDetails node = nodesMap.get(nodeName);
        node.isMaster = true;
        LOG.info("Chose node {} as a master from subnet {}.", nodeName, entry.getKey());
        numMastersChosen++;
        if (numMastersChosen == numMasters) {
          break;
        }
      }
    } else {
      // We do not have enough subnets. Simply pick enough masters.
      for (NodeDetails node : nodesMap.values()) {
        node.isMaster = true;
        LOG.info("Chose node {} as a master from subnet {}.",
                 node.nodeName, node.cloudInfo.subnet_id);
        numMastersChosen++;
        if (numMastersChosen == numMasters) {
          break;
        }
      }
    }
  }

  // Returns the start index for provisioning new nodes based on the current maximum node index.
  // If this is called for a new universe being created, then the start index will be 1.
  int getStartIndex(Universe universe) {
    Collection<NodeDetails> existingNodes = universe.getNodes();

    int maxNodeIdx = 0;
    for (NodeDetails node : existingNodes) {
      if (node.nodeIdx > maxNodeIdx) {
        maxNodeIdx = node.nodeIdx;
      }
    }

    return maxNodeIdx + 1;
  }

  /**
   * Helper API to convert the user form into task params.
   *
   * @param formData : Input form data.
   * @param universe : The universe details with which we are working.
   * @param customerId : Current customer's id.
   * @return: The universe task params.
   */
  private UniverseDefinitionTaskParams getTaskParams(
          Form<UniverseFormData> formData,
          Universe universe,
          Long customerId) {
    LOG.info("Initializing params for universe {} : {}.", universe.universeUUID, universe.name);
    // Setup the create universe task.
    UniverseDefinitionTaskParams taskParams = new UniverseDefinitionTaskParams();
    taskParams.universeUUID = universe.universeUUID;
    taskParams.numNodes = formData.get().replicationFactor;
    taskParams.ybServerPkg = formData.get().serverPackage;

    // Compose a unique name for the universe.
    taskParams.nodePrefix = Long.toString(customerId) + "-" + universe.name;

    // Fill in the user intent.
    taskParams.userIntent = new UserIntent();
    taskParams.userIntent.isMultiAZ = formData.get().isMultiAZ;
    LOG.debug("Setting isMultiAZ = " + taskParams.userIntent.isMultiAZ);
    taskParams.userIntent.preferredRegion = formData.get().preferredRegion;

    taskParams.userIntent.regionList = formData.get().regionList;
    LOG.debug("Added {} regions to placement info.", taskParams.userIntent.regionList.size());

    taskParams.userIntent.instanceType = formData.get().instanceType;

    // Set the replication factor.
    taskParams.userIntent.replicationFactor = formData.get().replicationFactor;

    // Compute and fill in the placement info.
    taskParams.placementInfo = getPlacementInfo(taskParams.userIntent);

    // Save the universe version to check for ops like edit universe as we did not lock the
    // universe and might get overwritten when this operation is finally run.
    taskParams.expectedUniverseVersion = universe.version;

    // Compute the nodes that should be configured for this operation.
    taskParams.newNodesSet = configureNewNodes(taskParams.nodePrefix,
                                               getStartIndex(universe),
                                               taskParams.numNodes,
                                               taskParams.userIntent.replicationFactor,
                                               taskParams.placementInfo);

    return taskParams;
  }

  private PlacementInfo getPlacementInfo(UserIntent userIntent) {
    // We only support a replication factor of 3.
    if (userIntent.replicationFactor != 3) {
      throw new RuntimeException("Replication factor must be 3");
    }
    // Make sure the preferred region is in the list of user specified regions.
    if (userIntent.preferredRegion != null &&
            !userIntent.regionList.contains(userIntent.preferredRegion)) {
      throw new RuntimeException("Preferred region " + userIntent.preferredRegion +
              " not in user region list");
    }

    // Create the placement info object.
    PlacementInfo placementInfo = new PlacementInfo();

    // Handle the single AZ deployment case.
    if (!userIntent.isMultiAZ) {
      // Select an AZ in the required region.
      List<AvailabilityZone> azList =
              AvailabilityZone.getAZsForRegion(userIntent.regionList.get(0));
      if (azList.isEmpty()) {
        throw new RuntimeException("No AZ found for region: " + userIntent.regionList.get(0));
      }
      Collections.shuffle(azList);
      UUID azUUID = azList.get(0).uuid;
      LOG.info("Using AZ {} out of {}", azUUID, azList.size());
      // Add all replicas into the same AZ.
      for (int idx = 0; idx < userIntent.replicationFactor; idx++) {
        addPlacementZone(azUUID, placementInfo);
      }
      return placementInfo;
    }

    // If one region is specified, pick all three AZs from it. Make sure there are enough regions.
    if (userIntent.regionList.size() == 1) {
      selectAndAddPlacementZones(userIntent.regionList.get(0), placementInfo, 3);
    } else if (userIntent.regionList.size() == 2) {
      // Pick two AZs from one of the regions (preferred region if specified).
      UUID preferredRegionUUID = userIntent.preferredRegion;
      // If preferred region was not specified, then pick the region that has at least 2 zones as
      // the preferred region.
      if (preferredRegionUUID == null) {
        if (AvailabilityZone.getAZsForRegion(userIntent.regionList.get(0)).size() >= 2) {
          preferredRegionUUID = userIntent.regionList.get(0);
        } else {
          preferredRegionUUID = userIntent.regionList.get(1);
        }
      }
      selectAndAddPlacementZones(preferredRegionUUID, placementInfo, 2);

      // Pick one AZ from the other region.
      UUID otherRegionUUID = userIntent.regionList.get(0).equals(preferredRegionUUID) ?
              userIntent.regionList.get(1) :
              userIntent.regionList.get(0);
      selectAndAddPlacementZones(otherRegionUUID, placementInfo, 1);
    } else if (userIntent.regionList.size() == 3) {
      // If the user has specified three regions, pick one AZ from each region.
      for (int idx = 0; idx < 3; idx++) {
        selectAndAddPlacementZones(userIntent.regionList.get(idx), placementInfo, 1);
      }
    } else {
      throw new RuntimeException("Unsupported placement, num regions " +
          userIntent.regionList.size() + " is more than replication factor of " +
          userIntent.replicationFactor);
    }

    return placementInfo;
  }

  private void selectAndAddPlacementZones(UUID regionUUID,
                                          PlacementInfo placementInfo,
                                          int numZones) {
    // Find the region object.
    Region region = Region.get(regionUUID);
    LOG.debug("Selecting and adding " + numZones + " zones in region " + region.name);
    // Find the AZs for the required region.
    List<AvailabilityZone> azList = AvailabilityZone.getAZsForRegion(regionUUID);
    if (azList.size() < numZones) {
      throw new RuntimeException("Need at least " + numZones + " zones but found only " +
              azList.size() + " for region " + region.name);
    }
    Collections.shuffle(azList);
    // Pick as many AZs as required.
    for (int idx = 0; idx < numZones; idx++) {
      addPlacementZone(azList.get(idx).uuid, placementInfo);
    }
  }

  private void addPlacementZone(UUID zone, PlacementInfo placementInfo) {
    // Get the zone, region and cloud.
    AvailabilityZone az = AvailabilityZone.find.byId(zone);
    Region region = az.region;
    Provider cloud = region.provider;
    // Find the placement cloud if it already exists, or create a new one if one does not exist.
    PlacementCloud placementCloud = null;
    for (PlacementCloud pCloud : placementInfo.cloudList) {
      if (pCloud.uuid.equals(cloud.uuid)) {
        LOG.debug("Found cloud: " + cloud.name);
        placementCloud = pCloud;
        break;
      }
    }
    if (placementCloud == null) {
      LOG.debug("Adding cloud: " + cloud.name);
      placementCloud = new PlacementCloud();
      placementCloud.uuid = cloud.uuid;
      // TODO: fix this hardcode by creating a 'code' attribute in the cloud object.
      placementCloud.name = "aws";
      placementInfo.cloudList.add(placementCloud);
    }

    // Find the placement region if it already exists, or create a new one.
    PlacementRegion placementRegion = null;
    for (PlacementRegion pRegion : placementCloud.regionList) {
      if (pRegion.uuid.equals(region.uuid)) {
        LOG.debug("Found region: " + region.name);
        placementRegion = pRegion;
        break;
      }
    }
    if (placementRegion == null) {
      LOG.debug("Adding region: " + region.name);
      placementRegion = new PlacementRegion();
      placementRegion.uuid = region.uuid;
      placementRegion.code = region.code;
      placementRegion.name = region.name;
      placementCloud.regionList.add(placementRegion);
    }

    // Find the placement AZ in the region if it already exists, or create a new one.
    PlacementAZ placementAZ = null;
    for (PlacementAZ pAz : placementRegion.azList) {
      if (pAz.uuid.equals(az.uuid)) {
        LOG.debug("Found az: " + az.name);
        placementAZ = pAz;
        break;
      }
    }
    if (placementAZ == null) {
      LOG.debug("Adding region: " + az.name);
      placementAZ = new PlacementAZ();
      placementAZ.uuid = az.uuid;
      placementAZ.name = az.name;
      placementAZ.replicationFactor = 0;
      placementAZ.subnet = az.subnet;
      placementRegion.azList.add(placementAZ);
    }
    placementAZ.replicationFactor++;
    LOG.debug("Setting az " + az.name + " replication factor = " + placementAZ.replicationFactor);
  }

  /**
   * Helper Method to fetch API Responses for Instance costs
   */
  private ObjectNode getUniverseCostUtil(Universe universe) throws Exception {
    Collection<NodeDetails> nodes = universe.getNodes();
    // TODO: only pick the newly configured nodes in case of the universe being edited.
    double instanceCostPerDay = 0;
    double universeCostPerDay = 0;
    for (NodeDetails node : nodes) {
      String regionCode = AvailabilityZone.find.byId(node.azUuid).region.code;
      // TODO: we do not currently store tenancy for the node.
      instanceCostPerDay = AWSCostUtil.getCostPerHour(node.cloudInfo.instance_type,
                           regionCode,
                           AWSConstants.Tenancy.Shared) * 24;
      universeCostPerDay += instanceCostPerDay;
    }
    Calendar currentCalender = Calendar.getInstance();
    int monthDays = currentCalender.getActualMaximum(Calendar.DAY_OF_MONTH);
    double costPerMonth = monthDays * universeCostPerDay;
    ObjectNode universeCostItem = Json.newObject();
    universeCostItem.put("costPerDay", universeCostPerDay);
    universeCostItem.put("costPerMonth", costPerMonth);
    universeCostItem.put("name", universe.name);
    universeCostItem.put("uuid", universe.universeUUID.toString());
    return universeCostItem;
  }
}
