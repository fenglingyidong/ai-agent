/*
Navicat MySQL Data Transfer

Source Server         : mall+ragagent
Source Server Version : 80046
Source Host           : localhost:3307
Source Database       : mall

Target Server Type    : MYSQL
Target Server Version : 80046
File Encoding         : 65001

Date: 2026-06-13 13:56:03
*/

SET FOREIGN_KEY_CHECKS=0;

-- ----------------------------
-- Table structure for brand
-- ----------------------------
DROP TABLE IF EXISTS `brand`;
CREATE TABLE `brand` (
  `id` bigint NOT NULL,
  `name` varchar(64) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of brand
-- ----------------------------
INSERT INTO `brand` VALUES ('1', 'Mall Labs');
INSERT INTO `brand` VALUES ('2', 'North Star');
INSERT INTO `brand` VALUES ('3', 'Aster');
INSERT INTO `brand` VALUES ('4', 'PeakLife');
INSERT INTO `brand` VALUES ('5', 'PureNest');
INSERT INTO `brand` VALUES ('6', 'FreshPeak');

-- ----------------------------
-- Table structure for cart_item
-- ----------------------------
DROP TABLE IF EXISTS `cart_item`;
CREATE TABLE `cart_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `checked` tinyint NOT NULL DEFAULT '1',
  `sku_name` varchar(128) NOT NULL DEFAULT '',
  `price` decimal(10,2) NOT NULL DEFAULT '0.00',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cart_user_sku` (`user_id`,`sku_id`)
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of cart_item
-- ----------------------------
INSERT INTO `cart_item` VALUES ('1', '92668751', '1001', '3', '1', '旗舰降噪耳机 黑色', '699.00');
INSERT INTO `cart_item` VALUES ('2', '92668751', '1002', '1', '1', '旗舰降噪耳机 银色', '729.00');
INSERT INTO `cart_item` VALUES ('13', '3079651', '3024', '2', '1', '硬面笔记本 A5 3本装', '39.00');
INSERT INTO `cart_item` VALUES ('15', '10001', '3020', '2', '1', '儿童积木套装 300片', '149.00');
INSERT INTO `cart_item` VALUES ('16', '10001', '1001', '1', '1', '旗舰降噪耳机 黑色', '699.00');

-- ----------------------------
-- Table structure for category
-- ----------------------------
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
  `id` bigint NOT NULL,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `name` varchar(64) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of category
-- ----------------------------
INSERT INTO `category` VALUES ('10', '0', '数码家电');
INSERT INTO `category` VALUES ('11', '0', '运动户外');
INSERT INTO `category` VALUES ('12', '0', '家居生活');
INSERT INTO `category` VALUES ('13', '0', '食品饮料');
INSERT INTO `category` VALUES ('14', '0', '美妆个护');
INSERT INTO `category` VALUES ('15', '0', '母婴玩具');
INSERT INTO `category` VALUES ('16', '0', '宠物用品');
INSERT INTO `category` VALUES ('17', '0', '图书文具');

-- ----------------------------
-- Table structure for consume_record
-- ----------------------------
DROP TABLE IF EXISTS `consume_record`;
CREATE TABLE `consume_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` varchar(128) NOT NULL,
  `consumed_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consume_message` (`message_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of consume_record
-- ----------------------------
INSERT INTO `consume_record` VALUES ('1', '25d3bcc0-4792-4777-84b7-2fbf177ac67f', '2026-04-29 20:37:23');
INSERT INTO `consume_record` VALUES ('2', '30edb97a-45da-49c3-b3b1-fc29ef0b6e3c', '2026-04-29 20:41:38');

-- ----------------------------
-- Table structure for mq_message
-- ----------------------------
DROP TABLE IF EXISTS `mq_message`;
CREATE TABLE `mq_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `message_id` varchar(64) NOT NULL,
  `exchange_name` varchar(128) NOT NULL,
  `routing_key` varchar(128) NOT NULL,
  `business_key` varchar(128) NOT NULL,
  `payload` text NOT NULL,
  `delay_millis` bigint DEFAULT NULL,
  `status` varchar(32) NOT NULL,
  `error_message` varchar(512) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_id` (`message_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of mq_message
-- ----------------------------
INSERT INTO `mq_message` VALUES ('1', 'c009fecf-dc78-48c0-bf72-3ffcafa6b287', 'mall.delay.exchange', 'order.close.delay', 'M202604292035465666369', 'M202604292035465666369', '30000', 'CONSUMED', null, '2026-04-29 20:35:47', '2026-04-29 20:36:17');
INSERT INTO `mq_message` VALUES ('2', '05b82f42-f07c-4cf6-bad1-a1ab680e4831', 'mall.delay.exchange', 'order.close.delay', 'M202604292036324858642', 'M202604292036324858642', '30000', 'CONSUMED', null, '2026-04-29 20:36:33', '2026-04-29 20:37:03');
INSERT INTO `mq_message` VALUES ('3', 'd456cbf9-b7b4-4bed-a48d-2302d0b33a2e', 'mall.delay.exchange', 'order.close.delay', 'M202604292036554949810', 'M202604292036554949810', '30000', 'CONSUMED', null, '2026-04-29 20:36:56', '2026-04-29 20:37:26');
INSERT INTO `mq_message` VALUES ('4', '37659d44-9670-4257-a907-358b0af8193b', 'mall.exchange', 'seckill.order.create', '25d3bcc0-4792-4777-84b7-2fbf177ac67f', '{\"requestId\":\"25d3bcc0-4792-4777-84b7-2fbf177ac67f\",\"activityId\":1,\"userId\":92903040,\"skuId\":1001,\"skuName\":\"Headphones Black Flash\",\"price\":499.00,\"quantity\":1}', null, 'CONSUMED', null, '2026-04-29 20:37:23', '2026-04-29 20:37:23');
INSERT INTO `mq_message` VALUES ('5', 'f37dfba5-700d-467f-8424-c0e40f206981', 'mall.delay.exchange', 'order.close.delay', 'S202604292037229726424', 'S202604292037229726424', '30000', 'CONSUMED', null, '2026-04-29 20:37:23', '2026-04-29 20:37:53');
INSERT INTO `mq_message` VALUES ('6', '4194ff7e-b3ac-4bc7-838c-3e7a64fbc54e', 'mall.exchange', 'seckill.order.result', '25d3bcc0-4792-4777-84b7-2fbf177ac67f', '{\"requestId\":\"25d3bcc0-4792-4777-84b7-2fbf177ac67f\",\"status\":\"SUCCESS\",\"orderSn\":\"S202604292037229726424\",\"message\":\"Success\"}', null, 'CONSUMED', null, '2026-04-29 20:37:23', '2026-04-29 20:37:23');
INSERT INTO `mq_message` VALUES ('7', '55d9da91-844a-42c3-bd15-350efe162b4d', 'mall.exchange', 'seckill.order.create', '30edb97a-45da-49c3-b3b1-fc29ef0b6e3c', '{\"requestId\":\"30edb97a-45da-49c3-b3b1-fc29ef0b6e3c\",\"activityId\":1,\"userId\":92903040,\"skuId\":2001,\"skuName\":\"Running Shoes Size 42 Flash\",\"price\":299.00,\"quantity\":1}', null, 'CONSUMED', null, '2026-04-29 20:41:38', '2026-04-29 20:41:38');
INSERT INTO `mq_message` VALUES ('8', 'a053151f-e308-41b1-86e3-595224d5ca0f', 'mall.exchange', 'seckill.order.result', '30edb97a-45da-49c3-b3b1-fc29ef0b6e3c', '{\"requestId\":\"30edb97a-45da-49c3-b3b1-fc29ef0b6e3c\",\"status\":\"SUCCESS\",\"orderSn\":\"S202604292037229726424\",\"message\":\"Success\"}', null, 'CONSUMED', null, '2026-04-29 20:41:38', '2026-04-29 20:41:38');

-- ----------------------------
-- Table structure for order_info
-- ----------------------------
DROP TABLE IF EXISTS `order_info`;
CREATE TABLE `order_info` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_sn` varchar(64) NOT NULL,
  `user_id` bigint NOT NULL,
  `status` varchar(32) NOT NULL,
  `total_amount` decimal(10,2) NOT NULL,
  `source` varchar(32) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_sn` (`order_sn`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of order_info
-- ----------------------------
INSERT INTO `order_info` VALUES ('1', 'M202604292032167063349', '92903040', 'CREATED', '6441.00', 'NORMAL', '2026-04-29 20:32:17', '2026-04-29 20:32:17');
INSERT INTO `order_info` VALUES ('2', 'M202604292035465666369', '92903040', 'CLOSED', '4983.00', 'NORMAL', '2026-04-29 20:35:47', '2026-04-29 20:36:17');
INSERT INTO `order_info` VALUES ('3', 'M202604292036324858642', '92903040', 'CANCELED', '699.00', 'NORMAL', '2026-04-29 20:36:32', '2026-04-29 20:36:37');
INSERT INTO `order_info` VALUES ('4', 'M202604292036554949810', '92903040', 'PAID', '2097.00', 'NORMAL', '2026-04-29 20:36:55', '2026-04-29 20:36:58');
INSERT INTO `order_info` VALUES ('5', 'S202604292037229726424', '92903040', 'CLOSED', '499.00', 'SECKILL', '2026-04-29 20:37:23', '2026-04-29 20:37:53');

-- ----------------------------
-- Table structure for order_item
-- ----------------------------
DROP TABLE IF EXISTS `order_item`;
CREATE TABLE `order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_sn` varchar(64) NOT NULL,
  `sku_id` bigint NOT NULL,
  `sku_name` varchar(128) NOT NULL,
  `price` decimal(10,2) NOT NULL,
  `quantity` int NOT NULL,
  `amount` decimal(10,2) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of order_item
-- ----------------------------
INSERT INTO `order_item` VALUES ('1', 'M202604292032167063349', '1002', '旗舰降噪耳机 银色', '729.00', '5', '3645.00');
INSERT INTO `order_item` VALUES ('2', 'M202604292032167063349', '1001', '旗舰降噪耳机 黑色', '699.00', '4', '2796.00');
INSERT INTO `order_item` VALUES ('3', 'M202604292035465666369', '1002', '旗舰降噪耳机 银色', '729.00', '3', '2187.00');
INSERT INTO `order_item` VALUES ('4', 'M202604292035465666369', '1001', '旗舰降噪耳机 黑色', '699.00', '4', '2796.00');
INSERT INTO `order_item` VALUES ('5', 'M202604292036324858642', '1001', '旗舰降噪耳机 黑色', '699.00', '1', '699.00');
INSERT INTO `order_item` VALUES ('6', 'M202604292036554949810', '1001', '旗舰降噪耳机 黑色', '699.00', '3', '2097.00');
INSERT INTO `order_item` VALUES ('7', 'S202604292037229726424', '1001', 'Headphones Black Flash', '499.00', '1', '499.00');

-- ----------------------------
-- Table structure for product_coupon
-- ----------------------------
DROP TABLE IF EXISTS `product_coupon`;
CREATE TABLE `product_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sku_id` bigint NOT NULL,
  `title` varchar(128) NOT NULL,
  `threshold_amount` decimal(10,2) NOT NULL DEFAULT '0.00',
  `discount_amount` decimal(10,2) NOT NULL,
  `expire_at` datetime NOT NULL,
  `stock` int NOT NULL DEFAULT '0',
  `enabled` tinyint NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `idx_product_coupon_sku` (`sku_id`),
  KEY `idx_product_coupon_expire` (`expire_at`)
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of product_coupon
-- ----------------------------
INSERT INTO `product_coupon` VALUES ('1', '1001', '60 off over 599', '599.00', '60.00', '2026-06-17 15:19:37', '500', '1');
INSERT INTO `product_coupon` VALUES ('2', '1002', '30 off over 299', '299.00', '30.00', '2026-06-02 15:19:37', '800', '1');
INSERT INTO `product_coupon` VALUES ('3', '2001', '50 off over 599', '599.00', '50.00', '2026-06-17 15:19:37', '300', '1');
INSERT INTO `product_coupon` VALUES ('4', '3001', '40 off over 299', '299.00', '40.00', '2026-06-07 15:19:37', '600', '1');
INSERT INTO `product_coupon` VALUES ('5', '3004', '无线鼠标满199减20', '199.00', '20.00', '2026-05-28 15:19:37', '200', '1');
INSERT INTO `product_coupon` VALUES ('6', '3006', '满199减20', '199.00', '20.00', '2026-06-12 15:19:16', '180', '1');
INSERT INTO `product_coupon` VALUES ('7', '3008', '满79减10', '79.00', '10.00', '2026-06-03 15:19:16', '1000', '1');
INSERT INTO `product_coupon` VALUES ('8', '3014', '满149减25', '149.00', '25.00', '2026-06-09 15:19:16', '250', '1');
INSERT INTO `product_coupon` VALUES ('9', '3016', '满129减20', '129.00', '20.00', '2026-05-30 15:19:16', '500', '1');
INSERT INTO `product_coupon` VALUES ('10', '3026', '满99减15', '99.00', '15.00', '2026-05-28 15:19:16', '220', '1');
INSERT INTO `product_coupon` VALUES ('31', '4002', '蓝牙耳机满299减30', '299.00', '30.00', '2026-06-30 23:59:59', '300', '1');
INSERT INTO `product_coupon` VALUES ('32', '4006', '充电宝满169减20', '169.00', '20.00', '2026-06-30 23:59:59', '500', '1');
INSERT INTO `product_coupon` VALUES ('33', '4014', '露营椅满199减25', '199.00', '25.00', '2026-06-30 23:59:59', '180', '1');
INSERT INTO `product_coupon` VALUES ('34', '4022', '空气炸锅满399减40', '399.00', '40.00', '2026-06-30 23:59:59', '160', '1');
INSERT INTO `product_coupon` VALUES ('35', '4034', '气泡水满99减12', '99.00', '12.00', '2026-06-30 23:59:59', '400', '1');
INSERT INTO `product_coupon` VALUES ('36', '4044', '电动牙刷满199减25', '199.00', '25.00', '2026-06-30 23:59:59', '220', '1');
INSERT INTO `product_coupon` VALUES ('37', '4052', '磁力片满199减30', '199.00', '30.00', '2026-06-30 23:59:59', '180', '1');
INSERT INTO `product_coupon` VALUES ('38', '4064', '宠物饮水机满169减20', '169.00', '20.00', '2026-06-30 23:59:59', '200', '1');
INSERT INTO `product_coupon` VALUES ('39', '4078', '马克笔满99减10', '99.00', '10.00', '2026-06-30 23:59:59', '260', '1');
INSERT INTO `product_coupon` VALUES ('40', '4080', '阅读书架满59减8', '59.00', '8.00', '2026-06-30 23:59:59', '300', '1');

-- ----------------------------
-- Table structure for review_summary
-- ----------------------------
DROP TABLE IF EXISTS `review_summary`;
CREATE TABLE `review_summary` (
  `sku_id` bigint NOT NULL,
  `average_rating` decimal(3,2) NOT NULL DEFAULT '0.00',
  `review_count` int NOT NULL DEFAULT '0',
  `good_rate` decimal(5,2) NOT NULL DEFAULT '0.00',
  `latest_review` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of review_summary
-- ----------------------------
INSERT INTO `review_summary` VALUES ('1001', '4.80', '1268', '98.20', 'Noise cancellation is excellent for commuting.');
INSERT INTO `review_summary` VALUES ('1002', '4.70', '638', '96.50', 'Comfortable fit and clean sound.');
INSERT INTO `review_summary` VALUES ('1003', '4.76', '418', '97.20', 'Battery life is longer than expected.');
INSERT INTO `review_summary` VALUES ('2001', '4.60', '842', '95.10', 'Lightweight shoes with stable support.');
INSERT INTO `review_summary` VALUES ('2002', '4.58', '520', '94.80', 'The white color looks clean and neat.');
INSERT INTO `review_summary` VALUES ('2003', '4.54', '301', '94.20', 'Good grip and not heavy.');
INSERT INTO `review_summary` VALUES ('3001', '4.90', '321', '99.00', 'Solid typing feel and sturdy build.');
INSERT INTO `review_summary` VALUES ('3002', '4.50', '416', '93.40', '静音红轴手感轻，适合办公室使用。');
INSERT INTO `review_summary` VALUES ('3003', '4.77', '214', '97.50', '无线连接稳定，紧凑布局节省桌面空间。');
INSERT INTO `review_summary` VALUES ('3004', '4.50', '416', '93.40', '按键安静，续航表现不错。');
INSERT INTO `review_summary` VALUES ('3005', '4.48', '233', '93.10', '握持舒适，外观简洁，携带方便。');
INSERT INTO `review_summary` VALUES ('3006', '4.70', '205', '96.20', 'Good size for commuting and business trips.');
INSERT INTO `review_summary` VALUES ('3007', '4.68', '190', '95.90', 'Storage pockets are practical.');
INSERT INTO `review_summary` VALUES ('3008', '4.85', '980', '98.70', 'Keeps drinks warm for a long time.');
INSERT INTO `review_summary` VALUES ('3009', '4.73', '512', '97.10', 'Larger capacity is convenient.');
INSERT INTO `review_summary` VALUES ('3010', '4.75', '560', '97.30', 'Cushioning is great for stretching.');
INSERT INTO `review_summary` VALUES ('3011', '4.66', '387', '95.60', 'Thickness is comfortable.');
INSERT INTO `review_summary` VALUES ('3012', '4.55', '188', '94.60', 'Small but loud enough for the room.');
INSERT INTO `review_summary` VALUES ('3013', '4.49', '144', '93.90', 'Good for bedside use.');
INSERT INTO `review_summary` VALUES ('3014', '4.65', '244', '95.80', 'Soft light and easy touch controls.');
INSERT INTO `review_summary` VALUES ('3015', '4.71', '176', '96.40', 'Eye comfort is noticeably better.');
INSERT INTO `review_summary` VALUES ('3016', '4.72', '133', '96.90', 'Tastes fresh and the pack size is handy.');
INSERT INTO `review_summary` VALUES ('3017', '4.78', '98', '97.60', 'Good for gifting and stock at home.');
INSERT INTO `review_summary` VALUES ('3018', '4.58', '167', '94.90', 'Gentle and comfortable for daily use.');
INSERT INTO `review_summary` VALUES ('3019', '4.61', '142', '95.10', 'Feels refreshing after washing.');
INSERT INTO `review_summary` VALUES ('3020', '4.83', '212', '98.10', 'Pieces fit well and instructions are clear.');
INSERT INTO `review_summary` VALUES ('3021', '4.76', '148', '97.20', 'Enough pieces to keep kids engaged.');
INSERT INTO `review_summary` VALUES ('3022', '4.64', '266', '95.40', 'Cats like the smell and texture.');
INSERT INTO `review_summary` VALUES ('3023', '4.69', '181', '95.80', 'Large pack is better value.');
INSERT INTO `review_summary` VALUES ('3024', '4.57', '320', '94.70', 'Paper is smooth and writing is clean.');
INSERT INTO `review_summary` VALUES ('3025', '4.60', '212', '95.00', 'Hard cover feels durable.');
INSERT INTO `review_summary` VALUES ('3026', '4.66', '280', '95.60', 'Boils water quickly and is easy to clean.');
INSERT INTO `review_summary` VALUES ('3027', '4.59', '190', '94.80', 'Capacity is enough for a family.');
INSERT INTO `review_summary` VALUES ('3028', '4.62', '230', '95.20', 'Vacuum effect is decent for home use.');
INSERT INTO `review_summary` VALUES ('3029', '4.68', '160', '95.90', 'Eight-piece set is very practical.');
INSERT INTO `review_summary` VALUES ('4001', '4.62', '286', '95.10', '佩戴轻便，日常通勤听歌够用。');
INSERT INTO `review_summary` VALUES ('4002', '4.70', '198', '96.20', '降噪效果明显，黑色外观简洁。');
INSERT INTO `review_summary` VALUES ('4003', '4.55', '320', '94.60', '运动记录清晰，续航比较稳定。');
INSERT INTO `review_summary` VALUES ('4004', '4.61', '176', '95.00', 'NFC 功能实用，粉色适合日常搭配。');
INSERT INTO `review_summary` VALUES ('4005', '4.58', '402', '94.90', '体积小，给手机补电很方便。');
INSERT INTO `review_summary` VALUES ('4006', '4.64', '238', '95.70', '容量大，适合出差和短途旅行。');
INSERT INTO `review_summary` VALUES ('4007', '4.50', '88', '93.60', '小房间观影够用，移动方便。');
INSERT INTO `review_summary` VALUES ('4008', '4.66', '72', '95.80', '画面清晰度不错，晚上观影效果更好。');
INSERT INTO `review_summary` VALUES ('4009', '4.67', '260', '96.10', '挂在显示器上不占桌面，光线柔和。');
INSERT INTO `review_summary` VALUES ('4010', '4.72', '144', '96.80', '无线调光方便，适合夜间办公。');
INSERT INTO `review_summary` VALUES ('4011', '4.59', '210', '94.80', '面料透气，跑步出汗后干得快。');
INSERT INTO `review_summary` VALUES ('4012', '4.63', '188', '95.40', '雾蓝颜色耐看，运动和日常都能穿。');
INSERT INTO `review_summary` VALUES ('4013', '4.57', '132', '94.60', '折叠后体积小，露营携带轻松。');
INSERT INTO `review_summary` VALUES ('4014', '4.65', '96', '95.70', '高背支撑好，坐感比普通款舒服。');
INSERT INTO `review_summary` VALUES ('4015', '4.56', '118', '94.30', '重量适中，新手上手容易。');
INSERT INTO `review_summary` VALUES ('4016', '4.69', '84', '96.00', '挥拍轻快，适合进阶练习。');
INSERT INTO `review_summary` VALUES ('4017', '4.52', '156', '93.80', '铝合金材质结实，短途徒步够用。');
INSERT INTO `review_summary` VALUES ('4018', '4.68', '92', '95.90', '双支更稳，长距离爬坡省力。');
INSERT INTO `review_summary` VALUES ('4019', '4.54', '240', '94.10', '基础保护够用，日常运动合适。');
INSERT INTO `review_summary` VALUES ('4020', '4.66', '162', '95.60', '支撑感更强，深蹲和跑步都能用。');
INSERT INTO `review_summary` VALUES ('4021', '4.60', '214', '95.00', '容量适合小家庭，旋钮操作简单。');
INSERT INTO `review_summary` VALUES ('4022', '4.71', '146', '96.40', '触控菜单直观，烤薯条很方便。');
INSERT INTO `review_summary` VALUES ('4023', '4.58', '173', '94.90', '纯棉触感柔软，灰色比较耐脏。');
INSERT INTO `review_summary` VALUES ('4024', '4.62', '158', '95.30', '尺寸适合大床，蓝色清爽。');
INSERT INTO `review_summary` VALUES ('4025', '4.55', '204', '94.40', '桌面使用不占地方，香薰扩散轻柔。');
INSERT INTO `review_summary` VALUES ('4026', '4.64', '137', '95.50', '水箱更大，木纹外观适合卧室。');
INSERT INTO `review_summary` VALUES ('4027', '4.51', '288', '93.90', '透明箱找东西方便，适合衣柜收纳。');
INSERT INTO `review_summary` VALUES ('4028', '4.60', '190', '95.00', '两只装更划算，容量适合被褥。');
INSERT INTO `review_summary` VALUES ('4029', '4.53', '216', '94.20', '黑色耐看，厨房台面更整齐。');
INSERT INTO `review_summary` VALUES ('4030', '4.61', '146', '95.10', '双层容量更大，瓶罐摆放稳定。');
INSERT INTO `review_summary` VALUES ('4031', '4.57', '332', '94.80', '原味百搭，早餐冲泡方便。');
INSERT INTO `review_summary` VALUES ('4032', '4.63', '244', '95.60', '水果坚果味更香，口感丰富。');
INSERT INTO `review_summary` VALUES ('4033', '4.50', '298', '93.70', '白桃味清爽，甜度不高。');
INSERT INTO `review_summary` VALUES ('4034', '4.56', '198', '94.40', '青柠味适合囤货，冰镇更好喝。');
INSERT INTO `review_summary` VALUES ('4035', '4.66', '168', '95.80', '中度烘焙酸苦平衡，手冲稳定。');
INSERT INTO `review_summary` VALUES ('4036', '4.70', '112', '96.20', '深烘风味浓，适合拿铁。');
INSERT INTO `review_summary` VALUES ('4037', '4.52', '246', '94.00', '草莓味香脆，独立小包装方便。');
INSERT INTO `review_summary` VALUES ('4038', '4.59', '176', '94.90', '混合装口味多，适合办公室零食。');
INSERT INTO `review_summary` VALUES ('4039', '4.55', '188', '94.50', '甜度低，独立包装方便携带。');
INSERT INTO `review_summary` VALUES ('4040', '4.62', '138', '95.30', '大包装适合长期吃，口感细腻。');
INSERT INTO `review_summary` VALUES ('4041', '4.58', '220', '94.80', '肤感清爽，日常通勤防晒够用。');
INSERT INTO `review_summary` VALUES ('4042', '4.64', '150', '95.60', '容量更大，涂身体也不心疼。');
INSERT INTO `review_summary` VALUES ('4043', '4.57', '242', '94.70', '震感柔和，入门使用简单。');
INSERT INTO `review_summary` VALUES ('4044', '4.68', '168', '95.90', '清洁模式多，续航稳定。');
INSERT INTO `review_summary` VALUES ('4045', '4.54', '206', '94.20', '吸收快，夏天用不粘腻。');
INSERT INTO `review_summary` VALUES ('4046', '4.63', '146', '95.40', '滋润度更高，秋冬使用舒服。');
INSERT INTO `review_summary` VALUES ('4047', '4.60', '184', '95.00', '乳化快，卸日常妆很干净。');
INSERT INTO `review_summary` VALUES ('4048', '4.66', '132', '95.80', '清爽不糊脸，容量也够用。');
INSERT INTO `review_summary` VALUES ('4049', '4.52', '220', '94.00', '洗后清爽，控油感明显。');
INSERT INTO `review_summary` VALUES ('4050', '4.59', '156', '94.80', '两支装更划算，适合囤货。');
INSERT INTO `review_summary` VALUES ('4051', '4.70', '146', '96.10', '磁力稳定，孩子拼搭兴趣高。');
INSERT INTO `review_summary` VALUES ('4052', '4.76', '104', '97.00', '片数多，能搭更复杂的造型。');
INSERT INTO `review_summary` VALUES ('4053', '4.62', '188', '95.30', '故事短小，亲子阅读方便。');
INSERT INTO `review_summary` VALUES ('4054', '4.69', '122', '96.20', '册数多，适合长期阅读。');
INSERT INTO `review_summary` VALUES ('4055', '4.55', '240', '94.50', '水分足，日常清洁好用。');
INSERT INTO `review_summary` VALUES ('4056', '4.61', '184', '95.20', '大包装更适合家庭囤货。');
INSERT INTO `review_summary` VALUES ('4057', '4.60', '136', '95.00', '三轮稳定，低龄儿童容易掌握。');
INSERT INTO `review_summary` VALUES ('4058', '4.67', '96', '95.90', '可折叠方便收纳，颜色好看。');
INSERT INTO `review_summary` VALUES ('4059', '4.66', '112', '95.80', '点读反应快，基础内容够用。');
INSERT INTO `review_summary` VALUES ('4060', '4.72', '78', '96.60', '绘本套装内容更丰富，适合启蒙。');
INSERT INTO `review_summary` VALUES ('4061', '4.52', '260', '94.10', '结团快，味道控制还可以。');
INSERT INTO `review_summary` VALUES ('4062', '4.59', '190', '94.90', '混合砂除味更好，容量适合多猫家庭。');
INSERT INTO `review_summary` VALUES ('4063', '4.61', '146', '95.20', '运行声音小，猫咪接受度高。');
INSERT INTO `review_summary` VALUES ('4064', '4.68', '98', '96.00', '过滤效果好，水箱容量更安心。');
INSERT INTO `review_summary` VALUES ('4065', '4.54', '210', '94.20', '小狗使用合适，颜色清爽。');
INSERT INTO `review_summary` VALUES ('4066', '4.60', '176', '95.00', '黑色耐脏，牵引手感稳定。');
INSERT INTO `review_summary` VALUES ('4067', '4.50', '240', '93.80', '猫咪爱抓，波浪造型不占地。');
INSERT INTO `review_summary` VALUES ('4068', '4.63', '138', '95.40', '大号更耐用，适合成年猫。');
INSERT INTO `review_summary` VALUES ('4069', '4.56', '218', '94.60', '除浮毛方便，刷头不硬。');
INSERT INTO `review_summary` VALUES ('4070', '4.64', '152', '95.50', '自清洁按钮省事，养宠家庭实用。');
INSERT INTO `review_summary` VALUES ('4071', '4.53', '320', '94.20', '出墨顺滑，黑色日常办公够用。');
INSERT INTO `review_summary` VALUES ('4072', '4.58', '214', '94.80', '颜色多，适合做手账和标记。');
INSERT INTO `review_summary` VALUES ('4073', '4.55', '198', '94.50', '三层分类清楚，桌面更整洁。');
INSERT INTO `review_summary` VALUES ('4074', '4.62', '146', '95.30', '金属材质更稳，四层容量够大。');
INSERT INTO `review_summary` VALUES ('4075', '4.57', '236', '94.70', 'A5 尺寸便携，错题整理方便。');
INSERT INTO `review_summary` VALUES ('4076', '4.63', '168', '95.40', 'B5 写字空间更大，适合学生。');
INSERT INTO `review_summary` VALUES ('4077', '4.56', '190', '94.60', '常用颜色齐全，适合基础绘画。');
INSERT INTO `review_summary` VALUES ('4078', '4.68', '118', '96.00', '60 色更丰富，渐变效果好。');
INSERT INTO `review_summary` VALUES ('4079', '4.52', '206', '94.00', '折叠收纳方便，读书时解放双手。');
INSERT INTO `review_summary` VALUES ('4080', '4.61', '154', '95.10', '加宽款更稳，厚书也能放。');

-- ----------------------------
-- Table structure for seckill_activity
-- ----------------------------
DROP TABLE IF EXISTS `seckill_activity`;
CREATE TABLE `seckill_activity` (
  `id` bigint NOT NULL,
  `name` varchar(128) NOT NULL,
  `start_at` datetime NOT NULL,
  `end_at` datetime NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of seckill_activity
-- ----------------------------
INSERT INTO `seckill_activity` VALUES ('1', 'Spring Flash Sale', '2026-05-18 14:19:37', '2026-05-19 15:19:37');
INSERT INTO `seckill_activity` VALUES ('2', '周末秒杀', '2026-05-18 13:19:16', '2026-05-20 15:19:16');
INSERT INTO `seckill_activity` VALUES ('3', '夜场秒杀', '2026-05-18 14:49:16', '2026-05-19 09:19:16');

-- ----------------------------
-- Table structure for seckill_order
-- ----------------------------
DROP TABLE IF EXISTS `seckill_order`;
CREATE TABLE `seckill_order` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `order_sn` varchar(64) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seckill_user` (`activity_id`,`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of seckill_order
-- ----------------------------
INSERT INTO `seckill_order` VALUES ('1', '1', '92903040', '1001', 'S202604292037229726424', '2026-04-29 20:37:23');

-- ----------------------------
-- Table structure for seckill_result
-- ----------------------------
DROP TABLE IF EXISTS `seckill_result`;
CREATE TABLE `seckill_result` (
  `request_id` varchar(64) NOT NULL,
  `status` varchar(32) NOT NULL,
  `order_sn` varchar(64) DEFAULT NULL,
  `message` varchar(255) DEFAULT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of seckill_result
-- ----------------------------
INSERT INTO `seckill_result` VALUES ('0a788886-c20c-4850-8fdc-952f2126d58b', 'FAILED', null, 'Duplicate purchase', '2026-04-29 20:42:16');
INSERT INTO `seckill_result` VALUES ('25d3bcc0-4792-4777-84b7-2fbf177ac67f', 'SUCCESS', 'S202604292037229726424', 'Success', '2026-04-29 20:37:23');
INSERT INTO `seckill_result` VALUES ('30edb97a-45da-49c3-b3b1-fc29ef0b6e3c', 'SUCCESS', 'S202604292037229726424', 'Success', '2026-04-29 20:41:38');
INSERT INTO `seckill_result` VALUES ('3789865c-584d-4798-861c-404f7c9d4033', 'FAILED', null, 'Duplicate purchase', '2026-04-29 20:41:35');
INSERT INTO `seckill_result` VALUES ('529e1348-abf6-407d-a0ec-9d5e3528e83e', 'FAILED', null, 'Duplicate purchase', '2026-04-29 20:41:37');
INSERT INTO `seckill_result` VALUES ('fccc95d7-7564-40d2-a7d4-0d554d2ec13f', 'FAILED', null, 'Duplicate purchase', '2026-04-29 20:41:33');

-- ----------------------------
-- Table structure for seckill_sku
-- ----------------------------
DROP TABLE IF EXISTS `seckill_sku`;
CREATE TABLE `seckill_sku` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `activity_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `sku_name` varchar(128) NOT NULL,
  `seckill_price` decimal(10,2) NOT NULL,
  `stock` int NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_sku` (`activity_id`,`sku_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of seckill_sku
-- ----------------------------
INSERT INTO `seckill_sku` VALUES ('1', '1', '1001', 'Headphones Black Flash', '499.00', '50');
INSERT INTO `seckill_sku` VALUES ('2', '1', '2001', 'Running Shoes Size 42 Flash', '299.00', '80');
INSERT INTO `seckill_sku` VALUES ('3', '2', '3001', '机械键盘 青轴 104键', '249.00', '60');
INSERT INTO `seckill_sku` VALUES ('4', '2', '3008', '不锈钢保温杯 500ml 白色', '59.00', '120');
INSERT INTO `seckill_sku` VALUES ('5', '3', '3014', '智能台灯 触控版 白色', '129.00', '70');
INSERT INTO `seckill_sku` VALUES ('6', '3', '3026', '电热水壶 1.5L', '99.00', '100');

-- ----------------------------
-- Table structure for sku
-- ----------------------------
DROP TABLE IF EXISTS `sku`;
CREATE TABLE `sku` (
  `id` bigint NOT NULL,
  `spu_id` bigint NOT NULL,
  `name` varchar(128) NOT NULL,
  `price` decimal(10,2) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of sku
-- ----------------------------
INSERT INTO `sku` VALUES ('1001', '100', '旗舰降噪耳机 黑色', '699.00');
INSERT INTO `sku` VALUES ('1002', '100', '旗舰降噪耳机 银色', '729.00');
INSERT INTO `sku` VALUES ('1003', '100', '旗舰降噪耳机 蓝牙升级版', '799.00');
INSERT INTO `sku` VALUES ('2001', '101', '轻量跑步鞋 42码', '399.00');
INSERT INTO `sku` VALUES ('2002', '101', '轻量跑步鞋 43码 白色', '399.00');
INSERT INTO `sku` VALUES ('2003', '101', '轻量跑步鞋 44码 灰色', '419.00');
INSERT INTO `sku` VALUES ('3001', '102', '机械键盘 青轴', '299.00');
INSERT INTO `sku` VALUES ('3002', '102', '机械键盘 红轴 87键', '129.00');
INSERT INTO `sku` VALUES ('3003', '102', '机械键盘 无线矮轴', '259.00');
INSERT INTO `sku` VALUES ('3004', '103', '无线鼠标 静音版', '89.00');
INSERT INTO `sku` VALUES ('3005', '103', '无线鼠标 人体工学版', '159.00');
INSERT INTO `sku` VALUES ('3006', '104', '城市通勤背包 20L 黑色', '259.00');
INSERT INTO `sku` VALUES ('3007', '104', '城市通勤背包 20L 灰色', '259.00');
INSERT INTO `sku` VALUES ('3008', '105', '不锈钢保温杯 500ml 白色', '89.00');
INSERT INTO `sku` VALUES ('3009', '105', '不锈钢保温杯 750ml 黑色', '109.00');
INSERT INTO `sku` VALUES ('3010', '106', '瑜伽训练垫 加厚款 紫色', '159.00');
INSERT INTO `sku` VALUES ('3011', '106', '瑜伽训练垫 加厚款 灰蓝', '159.00');
INSERT INTO `sku` VALUES ('3012', '107', '便携蓝牙音箱 深空灰', '189.00');
INSERT INTO `sku` VALUES ('3013', '107', '便携蓝牙音箱 迷你版', '159.00');
INSERT INTO `sku` VALUES ('3014', '108', '智能台灯 触控版 白色', '169.00');
INSERT INTO `sku` VALUES ('3015', '108', '智能台灯 护眼版 黑色', '219.00');
INSERT INTO `sku` VALUES ('3016', '109', '高蛋白坚果礼盒 12包', '139.00');
INSERT INTO `sku` VALUES ('3017', '109', '高蛋白坚果礼盒 24包', '239.00');
INSERT INTO `sku` VALUES ('3018', '110', '洁面补水套装 温和型', '199.00');
INSERT INTO `sku` VALUES ('3019', '110', '洁面补水套装 清爽型', '199.00');
INSERT INTO `sku` VALUES ('3020', '111', '儿童积木套装 300片', '149.00');
INSERT INTO `sku` VALUES ('3021', '111', '儿童积木套装 500片', '239.00');
INSERT INTO `sku` VALUES ('3022', '112', '猫粮全价粮 5kg', '189.00');
INSERT INTO `sku` VALUES ('3023', '112', '猫粮全价粮 10kg', '329.00');
INSERT INTO `sku` VALUES ('3024', '113', '硬面笔记本 A5 3本装', '39.00');
INSERT INTO `sku` VALUES ('3025', '113', '硬面笔记本 A4 2本装', '49.00');
INSERT INTO `sku` VALUES ('3026', '114', '电热水壶 1.5L', '119.00');
INSERT INTO `sku` VALUES ('3027', '114', '电热水壶 1.8L', '139.00');
INSERT INTO `sku` VALUES ('3028', '115', '真空收纳袋 4件套', '59.00');
INSERT INTO `sku` VALUES ('3029', '115', '真空收纳袋 8件套', '99.00');
INSERT INTO `sku` VALUES ('4001', '200', '入耳式蓝牙耳机 标准版 白色', '199.00');
INSERT INTO `sku` VALUES ('4002', '200', '入耳式蓝牙耳机 降噪版 黑色', '299.00');
INSERT INTO `sku` VALUES ('4003', '201', '智能手环 标准版 黑色', '169.00');
INSERT INTO `sku` VALUES ('4004', '201', '智能手环 NFC版 粉色', '229.00');
INSERT INTO `sku` VALUES ('4005', '202', '便携充电宝 10000mAh 白色', '129.00');
INSERT INTO `sku` VALUES ('4006', '202', '便携充电宝 20000mAh 黑色', '189.00');
INSERT INTO `sku` VALUES ('4007', '203', '迷你投影仪 基础版', '899.00');
INSERT INTO `sku` VALUES ('4008', '203', '迷你投影仪 高清版', '1299.00');
INSERT INTO `sku` VALUES ('4009', '204', '桌面显示器挂灯 标准版', '159.00');
INSERT INTO `sku` VALUES ('4010', '204', '桌面显示器挂灯 无线调光版', '229.00');
INSERT INTO `sku` VALUES ('4011', '205', '速干运动T恤 男款 黑色 L', '79.00');
INSERT INTO `sku` VALUES ('4012', '205', '速干运动T恤 女款 雾蓝 M', '79.00');
INSERT INTO `sku` VALUES ('4013', '206', '折叠露营椅 轻量款 卡其色', '139.00');
INSERT INTO `sku` VALUES ('4014', '206', '折叠露营椅 高背款 墨绿色', '199.00');
INSERT INTO `sku` VALUES ('4015', '207', '碳素羽毛球拍 入门款', '169.00');
INSERT INTO `sku` VALUES ('4016', '207', '碳素羽毛球拍 进阶款', '299.00');
INSERT INTO `sku` VALUES ('4017', '208', '登山杖 单支 铝合金', '89.00');
INSERT INTO `sku` VALUES ('4018', '208', '登山杖 双支 碳纤维', '219.00');
INSERT INTO `sku` VALUES ('4019', '209', '运动护膝 基础款 M码', '59.00');
INSERT INTO `sku` VALUES ('4020', '209', '运动护膝 支撑款 L码', '89.00');
INSERT INTO `sku` VALUES ('4021', '210', '空气炸锅 4L 旋钮款', '299.00');
INSERT INTO `sku` VALUES ('4022', '210', '空气炸锅 6L 触控款', '399.00');
INSERT INTO `sku` VALUES ('4023', '211', '床品四件套 纯棉 灰色 1.5m', '239.00');
INSERT INTO `sku` VALUES ('4024', '211', '床品四件套 纯棉 蓝色 1.8m', '269.00');
INSERT INTO `sku` VALUES ('4025', '212', '桌面香薰加湿器 300ml 白色', '99.00');
INSERT INTO `sku` VALUES ('4026', '212', '桌面香薰加湿器 500ml 木纹', '139.00');
INSERT INTO `sku` VALUES ('4027', '213', '透明收纳箱 35L 单只装', '49.00');
INSERT INTO `sku` VALUES ('4028', '213', '透明收纳箱 55L 两只装', '119.00');
INSERT INTO `sku` VALUES ('4029', '214', '厨房调料置物架 单层 黑色', '69.00');
INSERT INTO `sku` VALUES ('4030', '214', '厨房调料置物架 双层 银色', '109.00');
INSERT INTO `sku` VALUES ('4031', '215', '即食燕麦片 原味 1kg', '39.00');
INSERT INTO `sku` VALUES ('4032', '215', '即食燕麦片 水果坚果味 1kg', '49.00');
INSERT INTO `sku` VALUES ('4033', '216', '低糖气泡水 白桃味 12罐', '59.00');
INSERT INTO `sku` VALUES ('4034', '216', '低糖气泡水 青柠味 24罐', '99.00');
INSERT INTO `sku` VALUES ('4035', '217', '手冲咖啡豆 中度烘焙 250g', '69.00');
INSERT INTO `sku` VALUES ('4036', '217', '手冲咖啡豆 深度烘焙 500g', '119.00');
INSERT INTO `sku` VALUES ('4037', '218', '冻干水果脆 草莓味 80g', '29.00');
INSERT INTO `sku` VALUES ('4038', '218', '冻干水果脆 混合装 200g', '69.00');
INSERT INTO `sku` VALUES ('4039', '219', '无糖黑芝麻丸 12丸装', '49.00');
INSERT INTO `sku` VALUES ('4040', '219', '无糖黑芝麻丸 30丸装', '99.00');
INSERT INTO `sku` VALUES ('4041', '220', '清爽防晒乳 SPF50 50ml', '89.00');
INSERT INTO `sku` VALUES ('4042', '220', '清爽防晒乳 SPF50 100ml', '139.00');
INSERT INTO `sku` VALUES ('4043', '221', '电动牙刷 声波基础款', '129.00');
INSERT INTO `sku` VALUES ('4044', '221', '电动牙刷 智能清洁款', '229.00');
INSERT INTO `sku` VALUES ('4045', '222', '身体乳 清爽保湿 250ml', '59.00');
INSERT INTO `sku` VALUES ('4046', '222', '身体乳 滋润修护 500ml', '99.00');
INSERT INTO `sku` VALUES ('4047', '223', '卸妆膏 温和型 90g', '79.00');
INSERT INTO `sku` VALUES ('4048', '223', '卸妆膏 清爽型 120g', '109.00');
INSERT INTO `sku` VALUES ('4049', '224', '男士控油洗面奶 120g', '49.00');
INSERT INTO `sku` VALUES ('4050', '224', '男士控油洗面奶 套装 2支', '89.00');
INSERT INTO `sku` VALUES ('4051', '225', '儿童磁力片 68片', '129.00');
INSERT INTO `sku` VALUES ('4052', '225', '儿童磁力片 128片', '229.00');
INSERT INTO `sku` VALUES ('4053', '226', '儿童绘本套装 10册', '79.00');
INSERT INTO `sku` VALUES ('4054', '226', '儿童绘本套装 30册', '199.00');
INSERT INTO `sku` VALUES ('4055', '227', '婴儿湿巾 80抽 5包', '49.00');
INSERT INTO `sku` VALUES ('4056', '227', '婴儿湿巾 80抽 12包', '99.00');
INSERT INTO `sku` VALUES ('4057', '228', '儿童滑板车 三轮款 蓝色', '199.00');
INSERT INTO `sku` VALUES ('4058', '228', '儿童滑板车 可折叠款 粉色', '259.00');
INSERT INTO `sku` VALUES ('4059', '229', '早教点读笔 基础套装', '299.00');
INSERT INTO `sku` VALUES ('4060', '229', '早教点读笔 绘本套装', '399.00');
INSERT INTO `sku` VALUES ('4061', '230', '猫砂 豆腐砂 6L', '39.00');
INSERT INTO `sku` VALUES ('4062', '230', '猫砂 混合砂 12L', '79.00');
INSERT INTO `sku` VALUES ('4063', '231', '宠物饮水机 2L 静音款', '129.00');
INSERT INTO `sku` VALUES ('4064', '231', '宠物饮水机 3L 过滤款', '189.00');
INSERT INTO `sku` VALUES ('4065', '232', '狗狗牵引绳 S码 蓝色', '49.00');
INSERT INTO `sku` VALUES ('4066', '232', '狗狗牵引绳 M码 黑色', '59.00');
INSERT INTO `sku` VALUES ('4067', '233', '猫抓板 波浪款', '39.00');
INSERT INTO `sku` VALUES ('4068', '233', '猫抓板 大号立式款', '99.00');
INSERT INTO `sku` VALUES ('4069', '234', '宠物除毛刷 基础款', '39.00');
INSERT INTO `sku` VALUES ('4070', '234', '宠物除毛刷 自清洁款', '69.00');
INSERT INTO `sku` VALUES ('4071', '235', '中性笔套装 黑色 12支', '19.00');
INSERT INTO `sku` VALUES ('4072', '235', '中性笔套装 彩色 24支', '39.00');
INSERT INTO `sku` VALUES ('4073', '236', '桌面文件收纳架 三层 白色', '49.00');
INSERT INTO `sku` VALUES ('4074', '236', '桌面文件收纳架 金属四层 黑色', '79.00');
INSERT INTO `sku` VALUES ('4075', '237', '错题本 A5 4本装', '29.00');
INSERT INTO `sku` VALUES ('4076', '237', '错题本 B5 6本装', '49.00');
INSERT INTO `sku` VALUES ('4077', '238', '马克笔套装 24色', '49.00');
INSERT INTO `sku` VALUES ('4078', '238', '马克笔套装 60色', '119.00');
INSERT INTO `sku` VALUES ('4079', '239', '阅读书架 桌面折叠款', '39.00');
INSERT INTO `sku` VALUES ('4080', '239', '阅读书架 可调节加宽款', '69.00');

-- ----------------------------
-- Table structure for sku_stock
-- ----------------------------
DROP TABLE IF EXISTS `sku_stock`;
CREATE TABLE `sku_stock` (
  `sku_id` bigint NOT NULL,
  `stock` int NOT NULL,
  `locked_stock` int NOT NULL DEFAULT '0',
  PRIMARY KEY (`sku_id`),
  CONSTRAINT `sku_stock_chk_1` CHECK ((`stock` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of sku_stock
-- ----------------------------
INSERT INTO `sku_stock` VALUES ('1001', '200', '0');
INSERT INTO `sku_stock` VALUES ('1002', '120', '0');
INSERT INTO `sku_stock` VALUES ('1003', '80', '0');
INSERT INTO `sku_stock` VALUES ('2001', '300', '0');
INSERT INTO `sku_stock` VALUES ('2002', '260', '0');
INSERT INTO `sku_stock` VALUES ('2003', '180', '0');
INSERT INTO `sku_stock` VALUES ('3001', '180', '0');
INSERT INTO `sku_stock` VALUES ('3002', '260', '0');
INSERT INTO `sku_stock` VALUES ('3003', '150', '0');
INSERT INTO `sku_stock` VALUES ('3004', '500', '0');
INSERT INTO `sku_stock` VALUES ('3005', '220', '0');
INSERT INTO `sku_stock` VALUES ('3006', '150', '0');
INSERT INTO `sku_stock` VALUES ('3007', '140', '0');
INSERT INTO `sku_stock` VALUES ('3008', '500', '0');
INSERT INTO `sku_stock` VALUES ('3009', '380', '0');
INSERT INTO `sku_stock` VALUES ('3010', '220', '0');
INSERT INTO `sku_stock` VALUES ('3011', '210', '0');
INSERT INTO `sku_stock` VALUES ('3012', '160', '0');
INSERT INTO `sku_stock` VALUES ('3013', '140', '0');
INSERT INTO `sku_stock` VALUES ('3014', '130', '0');
INSERT INTO `sku_stock` VALUES ('3015', '90', '0');
INSERT INTO `sku_stock` VALUES ('3016', '300', '0');
INSERT INTO `sku_stock` VALUES ('3017', '180', '0');
INSERT INTO `sku_stock` VALUES ('3018', '260', '0');
INSERT INTO `sku_stock` VALUES ('3019', '240', '0');
INSERT INTO `sku_stock` VALUES ('3020', '160', '0');
INSERT INTO `sku_stock` VALUES ('3021', '110', '0');
INSERT INTO `sku_stock` VALUES ('3022', '340', '0');
INSERT INTO `sku_stock` VALUES ('3023', '200', '0');
INSERT INTO `sku_stock` VALUES ('3024', '600', '0');
INSERT INTO `sku_stock` VALUES ('3025', '420', '0');
INSERT INTO `sku_stock` VALUES ('3026', '190', '0');
INSERT INTO `sku_stock` VALUES ('3027', '170', '0');
INSERT INTO `sku_stock` VALUES ('3028', '310', '0');
INSERT INTO `sku_stock` VALUES ('3029', '210', '0');
INSERT INTO `sku_stock` VALUES ('4001', '260', '0');
INSERT INTO `sku_stock` VALUES ('4002', '180', '0');
INSERT INTO `sku_stock` VALUES ('4003', '320', '0');
INSERT INTO `sku_stock` VALUES ('4004', '210', '0');
INSERT INTO `sku_stock` VALUES ('4005', '420', '0');
INSERT INTO `sku_stock` VALUES ('4006', '260', '0');
INSERT INTO `sku_stock` VALUES ('4007', '90', '0');
INSERT INTO `sku_stock` VALUES ('4008', '60', '0');
INSERT INTO `sku_stock` VALUES ('4009', '300', '0');
INSERT INTO `sku_stock` VALUES ('4010', '180', '0');
INSERT INTO `sku_stock` VALUES ('4011', '360', '0');
INSERT INTO `sku_stock` VALUES ('4012', '340', '0');
INSERT INTO `sku_stock` VALUES ('4013', '180', '0');
INSERT INTO `sku_stock` VALUES ('4014', '120', '0');
INSERT INTO `sku_stock` VALUES ('4015', '220', '0');
INSERT INTO `sku_stock` VALUES ('4016', '140', '0');
INSERT INTO `sku_stock` VALUES ('4017', '260', '0');
INSERT INTO `sku_stock` VALUES ('4018', '130', '0');
INSERT INTO `sku_stock` VALUES ('4019', '420', '0');
INSERT INTO `sku_stock` VALUES ('4020', '300', '0');
INSERT INTO `sku_stock` VALUES ('4021', '160', '0');
INSERT INTO `sku_stock` VALUES ('4022', '120', '0');
INSERT INTO `sku_stock` VALUES ('4023', '180', '0');
INSERT INTO `sku_stock` VALUES ('4024', '160', '0');
INSERT INTO `sku_stock` VALUES ('4025', '260', '0');
INSERT INTO `sku_stock` VALUES ('4026', '190', '0');
INSERT INTO `sku_stock` VALUES ('4027', '500', '0');
INSERT INTO `sku_stock` VALUES ('4028', '280', '0');
INSERT INTO `sku_stock` VALUES ('4029', '300', '0');
INSERT INTO `sku_stock` VALUES ('4030', '220', '0');
INSERT INTO `sku_stock` VALUES ('4031', '460', '0');
INSERT INTO `sku_stock` VALUES ('4032', '340', '0');
INSERT INTO `sku_stock` VALUES ('4033', '380', '0');
INSERT INTO `sku_stock` VALUES ('4034', '240', '0');
INSERT INTO `sku_stock` VALUES ('4035', '200', '0');
INSERT INTO `sku_stock` VALUES ('4036', '150', '0');
INSERT INTO `sku_stock` VALUES ('4037', '420', '0');
INSERT INTO `sku_stock` VALUES ('4038', '260', '0');
INSERT INTO `sku_stock` VALUES ('4039', '330', '0');
INSERT INTO `sku_stock` VALUES ('4040', '210', '0');
INSERT INTO `sku_stock` VALUES ('4041', '300', '0');
INSERT INTO `sku_stock` VALUES ('4042', '180', '0');
INSERT INTO `sku_stock` VALUES ('4043', '260', '0');
INSERT INTO `sku_stock` VALUES ('4044', '170', '0');
INSERT INTO `sku_stock` VALUES ('4045', '360', '0');
INSERT INTO `sku_stock` VALUES ('4046', '220', '0');
INSERT INTO `sku_stock` VALUES ('4047', '260', '0');
INSERT INTO `sku_stock` VALUES ('4048', '190', '0');
INSERT INTO `sku_stock` VALUES ('4049', '300', '0');
INSERT INTO `sku_stock` VALUES ('4050', '210', '0');
INSERT INTO `sku_stock` VALUES ('4051', '200', '0');
INSERT INTO `sku_stock` VALUES ('4052', '130', '0');
INSERT INTO `sku_stock` VALUES ('4053', '320', '0');
INSERT INTO `sku_stock` VALUES ('4054', '180', '0');
INSERT INTO `sku_stock` VALUES ('4055', '460', '0');
INSERT INTO `sku_stock` VALUES ('4056', '300', '0');
INSERT INTO `sku_stock` VALUES ('4057', '150', '0');
INSERT INTO `sku_stock` VALUES ('4058', '120', '0');
INSERT INTO `sku_stock` VALUES ('4059', '110', '0');
INSERT INTO `sku_stock` VALUES ('4060', '80', '0');
INSERT INTO `sku_stock` VALUES ('4061', '500', '0');
INSERT INTO `sku_stock` VALUES ('4062', '300', '0');
INSERT INTO `sku_stock` VALUES ('4063', '190', '0');
INSERT INTO `sku_stock` VALUES ('4064', '140', '0');
INSERT INTO `sku_stock` VALUES ('4065', '360', '0');
INSERT INTO `sku_stock` VALUES ('4066', '300', '0');
INSERT INTO `sku_stock` VALUES ('4067', '420', '0');
INSERT INTO `sku_stock` VALUES ('4068', '220', '0');
INSERT INTO `sku_stock` VALUES ('4069', '380', '0');
INSERT INTO `sku_stock` VALUES ('4070', '260', '0');
INSERT INTO `sku_stock` VALUES ('4071', '600', '0');
INSERT INTO `sku_stock` VALUES ('4072', '420', '0');
INSERT INTO `sku_stock` VALUES ('4073', '340', '0');
INSERT INTO `sku_stock` VALUES ('4074', '220', '0');
INSERT INTO `sku_stock` VALUES ('4075', '500', '0');
INSERT INTO `sku_stock` VALUES ('4076', '320', '0');
INSERT INTO `sku_stock` VALUES ('4077', '360', '0');
INSERT INTO `sku_stock` VALUES ('4078', '180', '0');
INSERT INTO `sku_stock` VALUES ('4079', '420', '0');
INSERT INTO `sku_stock` VALUES ('4080', '260', '0');

-- ----------------------------
-- Table structure for spu
-- ----------------------------
DROP TABLE IF EXISTS `spu`;
CREATE TABLE `spu` (
  `id` bigint NOT NULL,
  `name` varchar(128) NOT NULL,
  `category_id` bigint NOT NULL,
  `brand_id` bigint NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of spu
-- ----------------------------
INSERT INTO `spu` VALUES ('100', '旗舰降噪耳机', '10', '1');
INSERT INTO `spu` VALUES ('101', '轻量跑步鞋', '11', '2');
INSERT INTO `spu` VALUES ('102', '机械键盘', '10', '1');
INSERT INTO `spu` VALUES ('103', '无线鼠标', '10', '1');
INSERT INTO `spu` VALUES ('104', '城市通勤背包', '11', '2');
INSERT INTO `spu` VALUES ('105', '不锈钢保温杯', '12', '3');
INSERT INTO `spu` VALUES ('106', '瑜伽训练垫', '11', '4');
INSERT INTO `spu` VALUES ('107', '便携蓝牙音箱', '10', '3');
INSERT INTO `spu` VALUES ('108', '智能台灯', '12', '4');
INSERT INTO `spu` VALUES ('109', '高蛋白坚果礼盒', '13', '6');
INSERT INTO `spu` VALUES ('110', '洁面补水套装', '14', '3');
INSERT INTO `spu` VALUES ('111', '儿童积木套装', '15', '5');
INSERT INTO `spu` VALUES ('112', '猫粮全价粮', '16', '6');
INSERT INTO `spu` VALUES ('113', '硬面笔记本', '17', '5');
INSERT INTO `spu` VALUES ('114', '电热水壶', '12', '4');
INSERT INTO `spu` VALUES ('115', '真空收纳袋', '12', '5');
INSERT INTO `spu` VALUES ('200', '入耳式蓝牙耳机', '10', '1');
INSERT INTO `spu` VALUES ('201', '智能手环', '10', '1');
INSERT INTO `spu` VALUES ('202', '便携充电宝', '10', '3');
INSERT INTO `spu` VALUES ('203', '迷你投影仪', '10', '4');
INSERT INTO `spu` VALUES ('204', '桌面显示器挂灯', '10', '3');
INSERT INTO `spu` VALUES ('205', '速干运动T恤', '11', '2');
INSERT INTO `spu` VALUES ('206', '折叠露营椅', '11', '2');
INSERT INTO `spu` VALUES ('207', '碳素羽毛球拍', '11', '4');
INSERT INTO `spu` VALUES ('208', '登山杖', '11', '4');
INSERT INTO `spu` VALUES ('209', '运动护膝', '11', '2');
INSERT INTO `spu` VALUES ('210', '空气炸锅', '12', '4');
INSERT INTO `spu` VALUES ('211', '床品四件套', '12', '5');
INSERT INTO `spu` VALUES ('212', '桌面香薰加湿器', '12', '3');
INSERT INTO `spu` VALUES ('213', '透明收纳箱', '12', '5');
INSERT INTO `spu` VALUES ('214', '厨房调料置物架', '12', '5');
INSERT INTO `spu` VALUES ('215', '即食燕麦片', '13', '6');
INSERT INTO `spu` VALUES ('216', '低糖气泡水', '13', '6');
INSERT INTO `spu` VALUES ('217', '手冲咖啡豆', '13', '6');
INSERT INTO `spu` VALUES ('218', '冻干水果脆', '13', '6');
INSERT INTO `spu` VALUES ('219', '无糖黑芝麻丸', '13', '6');
INSERT INTO `spu` VALUES ('220', '清爽防晒乳', '14', '3');
INSERT INTO `spu` VALUES ('221', '电动牙刷', '14', '1');
INSERT INTO `spu` VALUES ('222', '身体乳', '14', '3');
INSERT INTO `spu` VALUES ('223', '卸妆膏', '14', '3');
INSERT INTO `spu` VALUES ('224', '男士控油洗面奶', '14', '3');
INSERT INTO `spu` VALUES ('225', '儿童磁力片', '15', '5');
INSERT INTO `spu` VALUES ('226', '儿童绘本套装', '15', '5');
INSERT INTO `spu` VALUES ('227', '婴儿湿巾', '15', '5');
INSERT INTO `spu` VALUES ('228', '儿童滑板车', '15', '2');
INSERT INTO `spu` VALUES ('229', '早教点读笔', '15', '1');
INSERT INTO `spu` VALUES ('230', '猫砂', '16', '6');
INSERT INTO `spu` VALUES ('231', '宠物饮水机', '16', '1');
INSERT INTO `spu` VALUES ('232', '狗狗牵引绳', '16', '2');
INSERT INTO `spu` VALUES ('233', '猫抓板', '16', '5');
INSERT INTO `spu` VALUES ('234', '宠物除毛刷', '16', '3');
INSERT INTO `spu` VALUES ('235', '中性笔套装', '17', '5');
INSERT INTO `spu` VALUES ('236', '桌面文件收纳架', '17', '5');
INSERT INTO `spu` VALUES ('237', '错题本', '17', '5');
INSERT INTO `spu` VALUES ('238', '马克笔套装', '17', '5');
INSERT INTO `spu` VALUES ('239', '阅读书架', '17', '5');

-- ----------------------------
-- Table structure for tcc_fence_log
-- ----------------------------
DROP TABLE IF EXISTS `tcc_fence_log`;
CREATE TABLE `tcc_fence_log` (
  `xid` varchar(128) NOT NULL COMMENT 'global transaction id',
  `branch_id` bigint NOT NULL COMMENT 'branch transaction id',
  `action_name` varchar(64) NOT NULL COMMENT 'tcc action name',
  `status` tinyint NOT NULL COMMENT 'tried:1; committed:2; rollbacked:3; suspended:4',
  `gmt_create` datetime(3) NOT NULL COMMENT 'create time',
  `gmt_modified` datetime(3) NOT NULL COMMENT 'update time',
  PRIMARY KEY (`xid`,`branch_id`),
  KEY `idx_gmt_modified` (`gmt_modified`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of tcc_fence_log
-- ----------------------------

-- ----------------------------
-- Table structure for undo_log
-- ----------------------------
DROP TABLE IF EXISTS `undo_log`;
CREATE TABLE `undo_log` (
  `branch_id` bigint NOT NULL COMMENT 'branch transaction id',
  `xid` varchar(128) NOT NULL COMMENT 'global transaction id',
  `context` varchar(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` longblob NOT NULL COMMENT 'rollback info',
  `log_status` int NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created` datetime(6) NOT NULL COMMENT 'create datetime',
  `log_modified` datetime(6) NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of undo_log
-- ----------------------------

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id` bigint NOT NULL,
  `username` varchar(64) NOT NULL,
  `password` varchar(128) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of user
-- ----------------------------
INSERT INTO `user` VALUES ('10001', 'alice', 'demo123', '2026-04-29 17:56:05');
INSERT INTO `user` VALUES ('10002', 'bob', 'demo123', '2026-04-29 17:56:05');
INSERT INTO `user` VALUES ('10003', 'carol', 'demo123', '2026-04-29 17:56:05');
INSERT INTO `user` VALUES ('10004', 'dave', 'demo123', '2026-04-29 17:56:05');
INSERT INTO `user` VALUES ('10005', 'erin', 'demo123', '2026-04-29 17:56:05');
INSERT INTO `user` VALUES ('10006', 'frank', 'demo123', '2026-05-18 14:37:28');
INSERT INTO `user` VALUES ('10007', 'grace', 'demo123', '2026-05-18 14:37:28');
INSERT INTO `user` VALUES ('10008', 'henry', 'demo123', '2026-05-18 14:37:28');
INSERT INTO `user` VALUES ('10009', 'ivy', 'demo123', '2026-05-18 14:37:28');
INSERT INTO `user` VALUES ('10010', 'jack', 'demo123', '2026-05-18 14:37:28');
INSERT INTO `user` VALUES ('10011', 'kate', 'demo123', '2026-05-18 14:51:30');
INSERT INTO `user` VALUES ('10012', 'leo', 'demo123', '2026-05-18 14:51:30');
