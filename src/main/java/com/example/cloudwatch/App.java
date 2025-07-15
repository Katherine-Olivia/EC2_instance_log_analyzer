package com.example.cloudwatch;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.time.Instant;
import java.util.*;

public class App {
    public static void main(String[] args) {
        Region region = Region.EU_NORTH_1;

        String targetIp1 = "172.31.20.58"; // sample private ip of the EC2 instance 1
        String targetIp2 = "10.1.1.74";    // sample private ip of the EC2 instance 2

        try (
            Ec2Client ec2 = Ec2Client.builder().region(region).build();
            CloudWatchLogsClient logs = CloudWatchLogsClient.builder().region(region).build()
        ) {
            // Resolve VPC IDs
            String vpcId1 = getVpcIdFromPrivateIp(ec2, targetIp1);
            String vpcId2 = getVpcIdFromPrivateIp(ec2, targetIp2);
            System.out.println("Resolved VPC ID for " + targetIp1 + ": " + vpcId1);
            System.out.println("Resolved VPC ID for " + targetIp2 + ": " + vpcId2);

            ///BOTH THE LOGS FLOW TO THE SAME LOG GROUP
            String logGroupName = getLogGroupNameForVpc(ec2, vpcId1);
            System.out.println("Flow Log Group: " + logGroupName);

            Peers peers1 = queryPeersForIp(logs, logGroupName, targetIp1);
            Peers peers2 = queryPeersForIp(logs, logGroupName, targetIp2);

            // Summary 
            System.out.println("\n=== Communication Summary for IP: " + targetIp1 + " ===");
            peers1.printSummary();

            System.out.println("\n=== Communication Summary for IP: " + targetIp2 + " ===");
            peers2.printSummary();

            System.out.println("\n=== Bidirectional Communication Report ===");
            boolean ip1ToIp2 = peers1.outboundPeers.contains(targetIp2);
            boolean ip2ToIp1 = peers2.outboundPeers.contains(targetIp1);

            if (ip1ToIp2 && ip2ToIp1) {
                System.out.println("Perfect bidirectional communication between " + targetIp1 + " and " + targetIp2);
            } else if (ip1ToIp2) {
                System.out.println("Outbound only: " + targetIp1 + " sent traffic to " + targetIp2 + " but no return.");
            } else if (ip2ToIp1) {
                System.out.println("Inbound only: " + targetIp2 + " sent traffic to " + targetIp1 + " but no return.");
            } else {
                System.out.println("No communication detected between the two instances.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // HELPER CLASSES

    private static class Peers {
        Set<String> outboundPeers = new HashSet<>();
        Set<String> inboundPeers = new HashSet<>();
        int allowedCount = 0, errorCount = 0;

        void printSummary() {
            System.out.printf("Allowed: %d, Errors: %d%n", allowedCount, errorCount);
            System.out.println("Outbound Peers: " + outboundPeers);
            System.out.println("Inbound Peers: " + inboundPeers);
            System.out.println("Bidirectional Peers:");
            for (String peer : outboundPeers) {
                if (inboundPeers.contains(peer)) {
                    System.out.println(peer);
                }
            }
        }
    }

    private static Peers queryPeersForIp(CloudWatchLogsClient logs, String logGroupName, String targetIp) throws InterruptedException {
        Peers peers = new Peers();
        String query = "fields @timestamp, @message | filter @message like /^2/ | sort @timestamp desc | limit 1000";

        long endTime = Instant.now().getEpochSecond();
        long startTime = endTime - (60 * 10); //CHECKING ONLY FOR LAST 10 MINUTES

        StartQueryRequest startQueryReq = StartQueryRequest.builder()
                .logGroupName(logGroupName)
                .startTime(startTime)
                .endTime(endTime)
                .queryString(query)
                .build();

        String queryId = logs.startQuery(startQueryReq).queryId();

        GetQueryResultsResponse queryResults;
        do {
            Thread.sleep(2000);
            queryResults = logs.getQueryResults(GetQueryResultsRequest.builder().queryId(queryId).build());
        } while (queryResults.status() == QueryStatus.RUNNING);

        for (List<ResultField> fields : queryResults.results()) {
            String timestamp = "", message = "";

            for (ResultField field : fields) {
                if ("@timestamp".equals(field.field())) timestamp = field.value();
                if ("@message".equals(field.field())) message = field.value();
            }

            if (!message.isEmpty()) {
                try {
                    VpcLogEntry entry = parseVpcLog(message);
                    if (entry == null || !"ACCEPT".equalsIgnoreCase(entry.action)) continue;

                    boolean isOutbound = targetIp.equals(entry.srcIp);
                    boolean isInbound = targetIp.equals(entry.dstIp);

                    if (isOutbound) {
                        peers.outboundPeers.add(entry.dstIp);
                        peers.allowedCount++;
                    } else if (isInbound) {
                        peers.inboundPeers.add(entry.srcIp);
                        peers.allowedCount++;
                    }

                    String direction = isOutbound ? "Outbound" : "Inbound";
                    System.out.printf("[%s] %s: %s -> %s (Proto: %s, Ports: %s -> %s)%n",
                            timestamp, direction, entry.srcIp, entry.dstIp,
                            entry.protocol, entry.srcPort, entry.dstPort);

                } catch (Exception e) {
                    peers.errorCount++;
                    System.err.println("Error: " + e.getMessage());
                }
            }
        }
        return peers;
    }

    private static String getVpcIdFromPrivateIp(Ec2Client ec2, String privateIp) {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(Filter.builder().name("private-ip-address").values(privateIp).build())
                .build();

        DescribeInstancesResponse response = ec2.describeInstances(request);
        if (response.reservations().isEmpty()) {
            throw new RuntimeException("No EC2 found for IP: " + privateIp);
        }
        return response.reservations().get(0).instances().get(0).vpcId();
    }

    private static String getLogGroupNameForVpc(Ec2Client ec2, String vpcId) {
        DescribeFlowLogsRequest request = DescribeFlowLogsRequest.builder()
                .filter(Filter.builder().name("resource-id").values(vpcId).build())
                .build();

        DescribeFlowLogsResponse response = ec2.describeFlowLogs(request);
        if (response.flowLogs().isEmpty()) {
            throw new RuntimeException("No flow logs found for VPC: " + vpcId);
        }

        String logGroup = response.flowLogs().get(0).logGroupName();
        if (logGroup == null || logGroup.isEmpty()) {
            throw new RuntimeException("Flow log group name missing for VPC: " + vpcId);
        }

        return logGroup;
    }

    // Parser for VPC Flow Log entries
    private static final Map<String, Integer> VPC_FIELD_INDEX = Map.ofEntries(
            Map.entry("version", 0), Map.entry("accountId", 1), Map.entry("interfaceId", 2),
            Map.entry("srcIp", 3), Map.entry("dstIp", 4), Map.entry("srcPort", 5),
            Map.entry("dstPort", 6), Map.entry("protocol", 7), Map.entry("packets", 8),
            Map.entry("bytes", 9), Map.entry("startTime", 10), Map.entry("endTime", 11),
            Map.entry("action", 12), Map.entry("logStatus", 13)
    );

    private static class VpcLogEntry {
        String version, accountId, interfaceId, srcIp, dstIp, srcPort, dstPort;
        String protocol, packets, bytes, startTime, endTime, action, logStatus;
    }

    private static VpcLogEntry parseVpcLog(String logMsg) {
        String[] parts = logMsg.trim().split("\\s+");
        if (parts.length < 14) return null;

        VpcLogEntry entry = new VpcLogEntry();
        entry.version = parts[0]; 
        entry.accountId = parts[1]; 
        entry.interfaceId = parts[2];
        entry.srcIp = parts[3]; 
        entry.dstIp = parts[4]; 
        entry.srcPort = parts[5];
        entry.dstPort = parts[6]; 
        entry.protocol = parts[7]; 
        entry.packets = parts[8];
        entry.bytes = parts[9]; 
        entry.startTime = parts[10]; 
        entry.endTime = parts[11];
        entry.action = parts[12]; 
        entry.logStatus = parts[13];
        return entry;
    }
}