terraform {
  required_version = ">= 1.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# Variables
variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "us-east-1"
}

variable "bucket_name" {
  description = "S3 bucket name"
  type        = string
}

variable "proxy_instance_type" {
  description = "EC2 instance type for proxy server"
  type        = string
  default     = "t3.micro"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "production"
}

variable "enable_versioning" {
  description = "Enable versioning on S3 bucket"
  type        = bool
  default     = true
}

variable "enable_encryption" {
  description = "Enable encryption on S3 bucket"
  type        = bool
  default     = true
}

variable "proxy_management_username" {
  description = "Admin username for the proxy management panel"
  type        = string
  sensitive   = true
}

variable "proxy_management_password" {
  description = "Admin password for the proxy management panel"
  type        = string
  sensitive   = true
}

variable "proxy_management_session_secret" {
  description = "HMAC secret used to sign proxy management sessions"
  type        = string
  sensitive   = true
}

variable "auth_server_base_url" {
  description = "Base URL of the external auth server"
  type        = string
  default     = "http://localhost:8081"
}

variable "auth_identity_provisioning_key" {
  description = "Shared key authorizing identity creation on the external auth server"
  type        = string
  sensitive   = true
}

variable "allowed_origins" {
  description = "CORS origins allowed to call the proxy from browsers"
  type        = list(string)
  default     = []
}

variable "ssh_cidr_blocks" {
  description = "CIDR blocks allowed to SSH to the proxy instance. Empty disables SSH ingress."
  type        = list(string)
  default     = []
}

variable "stream_cidr_blocks" {
  description = "CIDR blocks allowed to publish the live stream to the ingest port."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "stream_analysis_endpoint" {
  description = "Webhook URL for the real-time analysis service. Leave empty to disable webhook delivery."
  type        = string
  default     = ""
}

variable "source_repo_url" {
  description = "Git repository URL containing the Java source for the bucket services"
  type        = string
  default     = ""
}

# Data source for latest Ubuntu AMI
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# VPC and Networking
resource "aws_vpc" "proxy_vpc" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.environment}-proxy-vpc"
  }
}

resource "aws_subnet" "proxy_subnet" {
  vpc_id            = aws_vpc.proxy_vpc.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = data.aws_availability_zones.available.names[0]

  tags = {
    Name = "${var.environment}-proxy-subnet"
  }
}

resource "aws_internet_gateway" "proxy_igw" {
  vpc_id = aws_vpc.proxy_vpc.id

  tags = {
    Name = "${var.environment}-proxy-igw"
  }
}

resource "aws_route_table" "proxy_rt" {
  vpc_id = aws_vpc.proxy_vpc.id

  route {
    cidr_block      = "0.0.0.0/0"
    gateway_id      = aws_internet_gateway.proxy_igw.id
  }

  tags = {
    Name = "${var.environment}-proxy-rt"
  }
}

resource "aws_route_table_association" "proxy_rta" {
  subnet_id      = aws_subnet.proxy_subnet.id
  route_table_id = aws_route_table.proxy_rt.id
}

data "aws_availability_zones" "available" {
  state = "available"
}

# Security Groups
resource "aws_security_group" "proxy_sg" {
  name        = "${var.environment}-proxy-sg"
  description = "Security group for S3 upload proxy server"
  vpc_id      = aws_vpc.proxy_vpc.id

  ingress {
    description = "HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS from anywhere"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  dynamic "ingress" {
    for_each = length(var.ssh_cidr_blocks) > 0 ? [1] : []
    content {
      description = "SSH from configured CIDR blocks"
      from_port   = 22
      to_port     = 22
      protocol    = "tcp"
      cidr_blocks = var.ssh_cidr_blocks
    }
  }

  dynamic "ingress" {
    for_each = length(var.stream_cidr_blocks) > 0 ? [1] : []
    content {
      description = "RTMP ingest from configured CIDR blocks"
      from_port   = 1935
      to_port     = 1935
      protocol    = "tcp"
      cidr_blocks = var.stream_cidr_blocks
    }
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.environment}-proxy-sg"
  }
}

# S3 Bucket with Private Access
resource "aws_s3_bucket" "private_bucket" {
  bucket = var.bucket_name

  tags = {
    Name        = var.bucket_name
    Environment = var.environment
  }
}

# Block all public access
resource "aws_s3_bucket_public_access_block" "private_bucket_pab" {
  bucket = aws_s3_bucket.private_bucket.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Enable versioning
resource "aws_s3_bucket_versioning" "private_bucket_versioning" {
  count  = var.enable_versioning ? 1 : 0
  bucket = aws_s3_bucket.private_bucket.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Server-side encryption
resource "aws_s3_bucket_server_side_encryption_configuration" "private_bucket_sse" {
  count  = var.enable_encryption ? 1 : 0
  bucket = aws_s3_bucket.private_bucket.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# IAM Role for Proxy Server
resource "aws_iam_role" "proxy_role" {
  name = "${var.environment}-proxy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })

  tags = {
    Name = "${var.environment}-proxy-role"
  }
}

# IAM Policy for S3 bucket access
resource "aws_iam_role_policy" "proxy_s3_policy" {
  name = "${var.environment}-proxy-s3-policy"
  role = aws_iam_role.proxy_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject"
        ]
        Resource = "${aws_s3_bucket.private_bucket.arn}/*"
      },
      {
        Effect = "Allow"
        Action = [
          "s3:ListBucket"
        ]
        Resource = aws_s3_bucket.private_bucket.arn
      }
    ]
  })
}

# Instance profile for proxy server
resource "aws_iam_instance_profile" "proxy_profile" {
  name = "${var.environment}-proxy-profile"
  role = aws_iam_role.proxy_role.name
}

# S3 Bucket Policy - Restrict access to proxy server only
resource "aws_s3_bucket_policy" "private_bucket_policy" {
  bucket = aws_s3_bucket.private_bucket.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DenyUnencryptedObjectUploads"
        Effect = "Deny"
        Principal = "*"
        Action = "s3:PutObject"
        Resource = "${aws_s3_bucket.private_bucket.arn}/*"
        Condition = {
          StringNotEquals = {
            "s3:x-amz-server-side-encryption" = "AES256"
          }
        }
      },
      {
        Sid    = "AllowProxyServerAccess"
        Effect = "Allow"
        Principal = {
          AWS = aws_iam_role.proxy_role.arn
        }
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.private_bucket.arn,
          "${aws_s3_bucket.private_bucket.arn}/*"
        ]
      }
    ]
  })

  depends_on = [aws_s3_bucket_public_access_block.private_bucket_pab]
}

# EC2 Instance for Proxy Server
resource "aws_instance" "proxy_server" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.proxy_instance_type
  iam_instance_profile   = aws_iam_instance_profile.proxy_profile.name
  subnet_id              = aws_subnet.proxy_subnet.id
  vpc_security_group_ids = [aws_security_group.proxy_sg.id]

  associate_public_ip_address = true

  user_data = templatefile("${path.module}/setup.sh", {
    bucket_name                          = aws_s3_bucket.private_bucket.id
    aws_region                           = var.aws_region
    source_repo_url_json                 = jsonencode(var.source_repo_url)
    auth_server_base_url_json            = jsonencode(var.auth_server_base_url)
    auth_identity_provisioning_key_json  = jsonencode(var.auth_identity_provisioning_key)
    proxy_management_username_json       = jsonencode(var.proxy_management_username)
    proxy_management_password_json       = jsonencode(var.proxy_management_password)
    proxy_management_session_secret_json = jsonencode(var.proxy_management_session_secret)
    allowed_origins_json                 = jsonencode(var.allowed_origins)
    stream_analysis_endpoint_json        = jsonencode(var.stream_analysis_endpoint != "" ? var.stream_analysis_endpoint : "http://127.0.0.1:9001/detect")
    stream_record_stream_sh              = file("${path.module}/../streaming/record_stream.sh")
    stream_analyze_stream_sh             = file("${path.module}/../streaming/analyze_stream.sh")
  })

  tags = {
    Name = "${var.environment}-proxy-server"
  }

  depends_on = [
    aws_internet_gateway.proxy_igw,
    aws_iam_role_policy.proxy_s3_policy
  ]
}

# Elastic IP for stable access
resource "aws_eip" "proxy_eip" {
  instance = aws_instance.proxy_server.id
  domain   = "vpc"

  tags = {
    Name = "${var.environment}-proxy-eip"
  }

  depends_on = [aws_internet_gateway.proxy_igw]
}

# Outputs
output "bucket_name" {
  description = "Name of the private S3 bucket"
  value       = aws_s3_bucket.private_bucket.id
}

output "bucket_arn" {
  description = "ARN of the S3 bucket"
  value       = aws_s3_bucket.private_bucket.arn
}

output "proxy_server_id" {
  description = "ID of the proxy server EC2 instance"
  value       = aws_instance.proxy_server.id
}

output "proxy_server_public_ip" {
  description = "Public IP of the proxy server"
  value       = aws_eip.proxy_eip.public_ip
}

output "proxy_server_private_ip" {
  description = "Private IP of the proxy server"
  value       = aws_instance.proxy_server.private_ip
}

output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.proxy_vpc.id
}
