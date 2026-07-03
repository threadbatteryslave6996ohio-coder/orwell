# AWS S3 Setup Guide for Keeboarder

Complete guide to set up AWS S3 bucket and IAM permissions for Keeboarder macOS client.

## Table of Contents

1. [Create S3 Bucket](#create-s3-bucket)
2. [Configure IAM User](#configure-iam-user)
3. [Set Bucket Policies](#set-bucket-policies)
4. [Enable Encryption](#enable-encryption)
5. [Configure Lifecycle](#configure-lifecycle-optional)
6. [Test Setup](#test-setup)

## Create S3 Bucket

### Option A: Using AWS CLI

```bash
# Create bucket
aws s3 mb s3://keeboarder-recordings --region us-east-1

# Enable versioning (for recovery)
aws s3api put-bucket-versioning \
  --bucket keeboarder-recordings \
  --versioning-configuration Status=Enabled

# Enable encryption
aws s3api put-bucket-encryption \
  --bucket keeboarder-recordings \
  --server-side-encryption-configuration '{
    "Rules": [{
      "ApplyServerSideEncryptionByDefault": {
        "SSEAlgorithm": "AES256"
      }
    }]
  }'
```

### Option B: Using AWS Console

1. Go to [AWS S3 Console](https://s3.console.aws.amazon.com/s3)
2. Click "Create bucket"
3. Enter bucket name: `keeboarder-recordings`
4. Select region: `us-east-1` (or your preferred region)
5. Leave all other settings default
6. Click "Create bucket"

## Configure IAM User

### Step 1: Create IAM User

1. Go to [AWS IAM Console](https://console.aws.amazon.com/iam/)
2. Click "Users" in the left sidebar
3. Click "Create user"
4. Enter username: `keeboarder-recorder`
5. Uncheck "Provide user access to the AWS Management Console" (CLI-only)
6. Click "Next: Permissions"

### Step 2: Attach Inline Policy

1. Click "Add inline policy"
2. Select "JSON" tab
3. Paste this policy:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::keeboarder-recordings",
        "arn:aws:s3:::keeboarder-recordings/*"
      ]
    }
  ]
}
```

4. Click "Review policy"
5. Enter name: `keeboarder-recorder-policy`
6. Click "Create policy"

### Step 3: Generate Access Keys

1. Click on the user `keeboarder-recorder`
2. Go to "Security credentials" tab
3. Scroll to "Access keys"
4. Click "Create access key"
5. Select "Command Line Interface (CLI)"
6. Check "I understand the above recommendation"
7. Click "Next"
8. Click "Create access key"
9. **Important**: Download CSV or copy the keys immediately (they won't be shown again!)

The CSV will contain:
- Access Key ID
- Secret Access Key

## Set Bucket Policies

### Restrict Access to Your IAM User

1. Go to [S3 Bucket Console](https://s3.console.aws.amazon.com/s3/buckets)
2. Click on `keeboarder-recordings` bucket
3. Go to "Permissions" tab
4. Scroll to "Bucket policy"
5. Click "Edit"
6. Paste this policy (replace `123456789012` with your AWS Account ID):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowKeeboarderRecorder",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:user/keeboarder-recorder"
      },
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject"
      ],
      "Resource": "arn:aws:s3:::keeboarder-recordings/*"
    },
    {
      "Sid": "AllowListBucket",
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:user/keeboarder-recorder"
      },
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::keeboarder-recordings"
    }
  ]
}
```

7. Click "Save changes"

**Optional: Prevent Public Access**

1. Go to "Permissions" tab
2. Scroll to "Block public access (bucket settings)"
3. Click "Edit"
4. Check all 4 boxes:
   - Block all public access
   - Block public access to ACLs
   - Block public access to bucket policies
   - Ignore all public ACLs
   - Restrict public bucket policies
5. Click "Save changes"

## Enable Encryption

### Server-Side Encryption (SSE-S3)

Using CLI:
```bash
aws s3api put-bucket-encryption \
  --bucket keeboarder-recordings \
  --server-side-encryption-configuration '{
    "Rules": [{
      "ApplyServerSideEncryptionByDefault": {
        "SSEAlgorithm": "AES256"
      }
    }]
  }'
```

Or using Console:
1. Go to bucket "Properties" tab
2. Scroll to "Default encryption"
3. Click "Edit"
4. Choose "Server-side encryption (SSE-S3)"
5. Click "Save changes"

### (Optional) KMS Encryption (SSE-KMS)

For stronger encryption using AWS KMS:

1. First create or select a KMS key in [KMS Console](https://console.aws.amazon.com/kms/)
2. Update IAM policy to include KMS permissions:

```json
{
  "Effect": "Allow",
  "Action": [
    "kms:Decrypt",
    "kms:GenerateDataKey",
    "kms:DescribeKey"
  ],
  "Resource": "arn:aws:kms:REGION:ACCOUNT:key/KEY_ID"
}
```

3. Configure bucket to use KMS key via Console or CLI

## Configure Lifecycle (Optional)

Automatically archive or delete old recordings to save costs.

### Archive to Glacier After 90 Days

```bash
aws s3api put-bucket-lifecycle-configuration \
  --bucket keeboarder-recordings \
  --lifecycle-configuration '{
    "Rules": [
      {
        "Id": "ArchiveToGlacier",
        "Filter": {"Prefix": "recordings/"},
        "Transitions": [
          {
            "Days": 90,
            "StorageClass": "GLACIER"
          }
        ],
        "Status": "Enabled"
      }
    ]
  }'
```

### Delete After 2 Years

```bash
aws s3api put-bucket-lifecycle-configuration \
  --bucket keeboarder-recordings \
  --lifecycle-configuration '{
    "Rules": [
      {
        "Id": "DeleteAfter2Years",
        "Filter": {"Prefix": "recordings/"},
        "Expiration": {
          "Days": 730
        },
        "Status": "Enabled"
      }
    ]
  }'
```

## Enable Versioning

Protect against accidental deletion:

```bash
aws s3api put-bucket-versioning \
  --bucket keeboarder-recordings \
  --versioning-configuration Status=Enabled
```

## Test Setup

### Test 1: Verify Bucket Access

```bash
aws s3 ls s3://keeboarder-recordings --region us-east-1
```

Should return empty or existing files without error.

### Test 2: Upload Test File

```bash
echo "test" > /tmp/test.txt
aws s3 cp /tmp/test.txt s3://keeboarder-recordings/test.txt --region us-east-1
```

Should succeed and show "upload: /tmp/test.txt to s3://..."

### Test 3: Verify File

```bash
aws s3 ls s3://keeboarder-recordings/test.txt --region us-east-1
```

Should show the test.txt file.

### Test 4: Delete Test File

```bash
aws s3 rm s3://keeboarder-recordings/test.txt --region us-east-1
```

### Test 5: Check Encryption

```bash
aws s3api head-bucket \
  --bucket keeboarder-recordings \
  --region us-east-1 \
  --query ServerSideEncryptionConfiguration
```

## Configure AWS CLI Locally

### Option 1: Using aws configure (Interactive)

```bash
aws configure
```

Enter:
```
AWS Access Key ID: AKIAIOSFODNN7EXAMPLE
AWS Secret Access Key: wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
Default region name: us-east-1
Default output format: json
```

This creates `~/.aws/credentials` and `~/.aws/config`.

### Option 2: Manual Setup

Create `~/.aws/credentials`:
```ini
[default]
aws_access_key_id = AKIAIOSFODNN7EXAMPLE
aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
```

Create `~/.aws/config`:
```ini
[default]
region = us-east-1
output = json
```

### Option 3: Using Named Profile

```bash
aws configure --profile keeboarder
```

Then update Keeboarder config.sh:
```bash
AWS_PROFILE="keeboarder"
```

## Security Best Practices

### 1. Use IAM User Instead of Root
- Never use AWS root account credentials
- Always create a dedicated IAM user for this purpose

### 2. Use Minimal Permissions
- Only grant s3:PutObject, s3:GetObject, s3:ListBucket
- Don't grant s3:* or full S3 access

### 3. Rotate Keys Regularly
```bash
# Deactivate old access key
aws iam update-access-key-status \
  --user-name keeboarder-recorder \
  --access-key-id AKIAIOSFODNN7EXAMPLE \
  --status Inactive

# Create new one
aws iam create-access-key --user-name keeboarder-recorder

# Delete old one after confirming new one works
aws iam delete-access-key \
  --user-name keeboarder-recorder \
  --access-key-id AKIAIOSFODNN7EXAMPLE
```

### 4. Enable MFA (Optional but Recommended)
```bash
aws iam enable-mfa-device \
  --user-name keeboarder-recorder \
  --serial-number arn:aws:iam::123456789012:mfa/keeboarder
```

### 5. Monitor Access
```bash
# View access key usage
aws iam get-credential-report

# Check S3 access logs
aws s3api get-bucket-logging --bucket keeboarder-recordings
```

## Troubleshooting

### Error: "Access Denied"
- Check IAM user has correct permissions
- Verify access key ID and secret
- Ensure bucket policy allows the IAM user

### Error: "NoSuchBucket"
- Verify bucket name matches config.sh
- Check bucket region
- Ensure bucket exists: `aws s3 ls | grep keeboarder`

### Error: "The bucket does not allow ACLs"
- This is normal with modern S3
- Use bucket policies instead (already configured)

### Error: "RequestLimitExceeded"
- You're uploading too many files at once
- Reduce `UPLOAD_BATCH_SIZE` in config.sh

## Cost Estimation

Rough monthly costs for 1GB/day upload:

- **Storage (30GB/month)**: ~$0.70
- **Data Transfer**: $0 (upload only)
- **Request costs**: < $0.01
- **Total**: ~$0.71/month

With Glacier archival after 90 days:
- **S3 Standard (3 months)**: ~$2.10
- **Glacier (9 months)**: ~$0.45
- **Total**: ~$2.55/month

## Additional Resources

- [AWS S3 Documentation](https://docs.aws.amazon.com/s3/)
- [AWS IAM Best Practices](https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html)
- [AWS S3 Pricing](https://aws.amazon.com/s3/pricing/)
- [AWS S3 Security](https://docs.aws.amazon.com/AmazonS3/latest/userguide/security.html)
