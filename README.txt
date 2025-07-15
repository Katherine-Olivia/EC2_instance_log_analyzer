README.txt
===========

Title:  
AWS VPC Flow Log Communication Analyzer for Two EC2 Instances in Different VPCs

Overview:  
This code retrieves and analyzes AWS VPC Flow Logs to detect and summarize communication between two EC2 instances located in different Virtual Private Clouds (VPCs). It uses the AWS SDK for Java (v2) and CloudWatch Logs Insights to extract and evaluate network traffic based on private IP addresses.

Purpose:  
- Analyze traffic between two EC2 instances across different VPCs.  
- Determine if the communication is bidirectional.  
- Display summaries of accepted logs and involved peers for each instance.

Flow:  
1. Accept two EC2 private IP addresses (each from a different VPC).  
2. Resolve VPC IDs for each private IP.  
3. Identify the common CloudWatch Logs group receiving VPC Flow Logs.  
4. Fetch flow logs for the last 10 minutes.  
5. Filter only accepted entries.  
6. Summarize outbound and inbound peers.  
7. Report if traffic is bidirectional.

Expected Output:  
- Inbound and outbound peers per IP.  
- Count of allowed traffic and parsing errors.  
- Message confirming if two-way traffic exists.

Main File:  
App.java  
- Accepts two private IPs as input.  
- Uses EC2 SDK to resolve VPC IDs.  
- Identifies log group.  
- Fetches logs from CloudWatch.  
- Analyzes accepted logs to track communication peers.  
- Prints traffic summary and directionality report.

Sample Output:  
Resolved VPC ID for 172.31.20.58: vpc-xxxxxx  
Resolved VPC ID for 172.31.94.190: vpc-yyyyyy  
Flow Log Group: /vpc/flowlogs  

=== Communication Summary for IP: 172.31.20.58 ===  
Allowed: 5, Errors: 0  
Outbound Peers: [172.31.94.190]  
Inbound Peers: []  
Bidirectional Peers:  

=== Communication Summary for IP: 172.31.94.190 ===  
Allowed: 4, Errors: 0  
Outbound Peers: []  
Inbound Peers: [172.31.20.58]  
Bidirectional Peers:  

=== Bidirectional Communication Report ===  
Outbound only: 172.31.20.58 sent traffic to 172.31.94.190 but no return.

Assumptions:  
- The two EC2 instances are from different VPCs.  
- VPC Flow Logs are enabled and sent to a common log group.  
- Proper connectivity (e.g., VPC peering) is already configured.  
- AWS credentials have required EC2 and CloudWatch permissions.

To Run (Using Maven):  
1. Compile the code:  
   mvn clean compile  

2. Package the code:  
   mvn clean install  

3. Execute the code:  
   mvn exec:java -Dexec.mainClass="com.example.cloudwatch.App"  

If needed, ensure the following plugin exists in pom.xml:
<plugin>
  <groupId>org.codehaus.mojo</groupId>
  <artifactId>exec-maven-plugin</artifactId>
  <version>3.1.0</version>
  <configuration>
    <mainClass>com.example.cloudwatch.App</mainClass>
  </configuration>
</plugin>

Dependencies:  
- Java 11 or later  
- AWS SDK for Java v2  
- Maven  

Author:  
Katherine Olivia  
Summer Intern'25 - Site24x7  
Dated: 02.06.2025
