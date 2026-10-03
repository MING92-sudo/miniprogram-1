# scripts/fixtures

平台联调脚本（scripts/test-full.ps1 的 2.3/2.4 multipart 上传）使用的**合成测试文件**：
由脚本生成的空白 PDF（各 <2 KB），不含任何真实合同/证书/个人信息，故可入库。

AGENTS §2.5 的"证书 PDF、合同 PDF 不得提交"指**真实**数据文件；真实件只放本地 .env 同级目录且不入库。

- cert-test.pdf：2.4 维保人员登记的 certificateFile 占位
- contract-test.pdf：2.3 维保服务关系的 contractFile 占位
