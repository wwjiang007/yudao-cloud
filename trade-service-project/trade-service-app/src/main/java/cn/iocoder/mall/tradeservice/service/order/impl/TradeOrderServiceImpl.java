package cn.iocoder.mall.tradeservice.service.order.impl;

import cn.iocoder.common.framework.exception.util.ServiceExceptionUtil;
import cn.iocoder.common.framework.util.CollectionUtils;
import cn.iocoder.common.framework.util.DateUtil;
import cn.iocoder.common.framework.util.MathUtil;
import cn.iocoder.mall.productservice.enums.sku.ProductSkuDetailFieldEnum;
import cn.iocoder.mall.productservice.rpc.sku.dto.ProductSkuRespDTO;
import cn.iocoder.mall.promotion.api.rpc.price.dto.PriceProductCalcReqDTO;
import cn.iocoder.mall.promotion.api.rpc.price.dto.PriceProductCalcRespDTO;
import cn.iocoder.mall.tradeservice.client.product.ProductSkuClient;
import cn.iocoder.mall.tradeservice.client.promotion.CouponCardClient;
import cn.iocoder.mall.tradeservice.client.promotion.PriceClient;
import cn.iocoder.mall.tradeservice.client.user.UserAddressClient;
import cn.iocoder.mall.tradeservice.dal.mysql.dataobject.order.TradeOrderDO;
import cn.iocoder.mall.tradeservice.dal.mysql.dataobject.order.TradeOrderItemDO;
import cn.iocoder.mall.tradeservice.dal.mysql.mapper.order.TradeOrderItemMapper;
import cn.iocoder.mall.tradeservice.dal.mysql.mapper.order.TradeOrderMapper;
import cn.iocoder.mall.tradeservice.enums.logistics.LogisticsDeliveryTypeEnum;
import cn.iocoder.mall.tradeservice.enums.order.TradeOrderAfterSaleStatusEnum;
import cn.iocoder.mall.tradeservice.enums.order.TradeOrderStatusEnum;
import cn.iocoder.mall.tradeservice.rpc.order.dto.TradeOrderCreateReqDTO;
import cn.iocoder.mall.tradeservice.service.order.TradeOrderService;
import cn.iocoder.mall.userservice.rpc.address.dto.UserAddressRespDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.mall.tradeservice.enums.OrderErrorCodeConstants.*;
import static cn.iocoder.mall.userservice.enums.UserErrorCodeConstants.*;

/**
 * 交易订单 Service 实现
 */
@Service
public class TradeOrderServiceImpl implements TradeOrderService {

    @Autowired
    private TradeOrderMapper tradeOrderMapper;
    @Autowired
    private TradeOrderItemMapper tradeOrderItemMapper;

    @Autowired // 注入自己，用于调用事务方法
    private TradeOrderServiceImpl self;

    @Autowired
    private UserAddressClient userAddressClient;
    @Autowired
    private ProductSkuClient productSkuClient;
    @Autowired
    private PriceClient priceClient;
    @Autowired
    private CouponCardClient couponCardClient;

    @Override
//    @GlobalTransactional TODO 芋艿，使用 seata 实现分布式事务
    public Integer createTradeOrder(TradeOrderCreateReqDTO createReqDTO) {
        // 获得收件地址
        UserAddressRespDTO userAddressRespDTO = userAddressClient.getUserAddress(createReqDTO.getUserAddressId(),
                createReqDTO.getUserId());
        if (userAddressRespDTO == null) {
            throw ServiceExceptionUtil.exception(USER_ADDRESS_NOT_FOUND);
        }
        // 获得商品信息
        List<ProductSkuRespDTO> listProductSkus = productSkuClient.listProductSkus(
                CollectionUtils.convertSet(createReqDTO.getOrderItems(), TradeOrderCreateReqDTO.OrderItem::getSkuId),
                ProductSkuDetailFieldEnum.SPU.getField());
        if (listProductSkus.size() != createReqDTO.getOrderItems().size()) { // 校验获得的数量，是否匹配
            throw ServiceExceptionUtil.exception(ORDER_GET_GOODS_INFO_INCORRECT);
        }
        // 价格计算
        PriceProductCalcRespDTO priceProductCalcRespDTO = priceClient.calcProductPrice(createReqDTO.getUserId(),
                createReqDTO.getOrderItems().stream().map(orderItem -> new PriceProductCalcReqDTO.Item().setSkuId(orderItem.getSkuId())
                        .setQuantity(orderItem.getQuantity()).setSelected(true)).collect(Collectors.toList()),
                createReqDTO.getCouponCardId());

        // TODO 芋艿，扣除库存

        // 标记优惠劵已使用
        if (createReqDTO.getCouponCardId() != null) {
            couponCardClient.useCouponCard(createReqDTO.getUserId(), createReqDTO.getCouponCardId());
        }

        // 创建交易订单（本地事务）
        Integer tradeOrderId = self.createTradeOrder0(createReqDTO, listProductSkus, priceProductCalcRespDTO, userAddressRespDTO);

        // 创建支付订单，对接支付服务
        createPayTransaction();
        return tradeOrderId;
    }

    @Transactional
    public Integer createTradeOrder0(TradeOrderCreateReqDTO createReqDTO, List<ProductSkuRespDTO> listProductSkus,
                                     PriceProductCalcRespDTO priceProductCalcRespDTO, UserAddressRespDTO userAddressRespDTO) {
        // 构建 TradeOrderDO 对象，并进行保存
        TradeOrderDO tradeOrderDO = new TradeOrderDO();
        // 1. 基本信息
        tradeOrderDO.setUserId(createReqDTO.getUserId()).setOrderNo(generateTradeOrderNo())
                .setOrderStatus(TradeOrderStatusEnum.WAITING_PAYMENT.getValue()).setRemark(createReqDTO.getRemark());
        // 2. 价格 + 支付基本信息
        tradeOrderDO.setBuyPrice(priceProductCalcRespDTO.getFee().getBuyTotal())
                .setDiscountPrice(priceProductCalcRespDTO.getFee().getDiscountTotal())
                .setLogisticsPrice(priceProductCalcRespDTO.getFee().getPostageTotal())
                .setPresentPrice(priceProductCalcRespDTO.getFee().getPresentTotal())
                .setPayPrice(0).setRefundPrice(0);
        // 3. 收件 + 物流基本信息
        tradeOrderDO.setDeliveryType(LogisticsDeliveryTypeEnum.EXPRESS.getDeliveryType())
                .setReceiverName(userAddressRespDTO.getName()).setReceiverMobile(userAddressRespDTO.getMobile())
                .setReceiverAreaCode(userAddressRespDTO.getAreaCode()).setReceiverDetailAddress(userAddressRespDTO.getDetailAddress());
        // 4. 售后基本信息
        tradeOrderDO.setAfterSaleStatus(TradeOrderAfterSaleStatusEnum.NULL.getStatus());
        // 5. 营销基本信息
        tradeOrderDO.setCouponCardId(createReqDTO.getCouponCardId());
        // 最终保存
        tradeOrderMapper.insert(tradeOrderDO);

        // 创建 TradeOrderItemDO 数组，并进行保存
        List<TradeOrderItemDO> tradeOrderItemDOs = new ArrayList<>(listProductSkus.size());
        Map<Integer, ProductSkuRespDTO> listProductSkuMap = CollectionUtils.convertMap(listProductSkus, ProductSkuRespDTO::getId);
        Map<Integer, PriceProductCalcRespDTO.Item> priceItemMap = new HashMap<>(); // 商品 SKU 价格的映射
        priceProductCalcRespDTO.getItemGroups().forEach(itemGroup ->
                itemGroup.getItems().forEach(item -> priceItemMap.put(item.getSkuId(), item)));
        for (TradeOrderCreateReqDTO.OrderItem orderItem : createReqDTO.getOrderItems()) {
            TradeOrderItemDO tradeOrderItemDO = new TradeOrderItemDO();
            tradeOrderItemDOs.add(tradeOrderItemDO);
            // 1. 基本信息
            tradeOrderItemDO.setOrderId(tradeOrderDO.getId()).setStatus(tradeOrderDO.getOrderStatus());
            // 2. 商品基本信息
            ProductSkuRespDTO productSkuRespDTO = listProductSkuMap.get(orderItem.getSkuId());
            tradeOrderItemDO.setSpuId(productSkuRespDTO.getSpuId()).setSkuId(productSkuRespDTO.getId())
                    .setSkuName(productSkuRespDTO.getSpu().getName())
                    .setSkuImage(CollectionUtils.getFirst(productSkuRespDTO.getSpu().getPicUrls()))
                    .setQuantity(orderItem.getQuantity());
            // 3. 价格 + 支付基本信息
            PriceProductCalcRespDTO.Item priceItem = priceItemMap.get(orderItem.getSkuId());
            tradeOrderItemDO.setOriginPrice(priceItem.getOriginPrice()).setBuyPrice(priceItem.getBuyPrice())
                    .setPresentPrice(priceItem.getPresentPrice()).setBuyTotal(priceItem.getBuyTotal())
                    .setDiscountTotal(priceItem.getDiscountTotal()).setPresentTotal(priceItem.getPresentTotal())
                    .setRefundTotal(0);
            // 4. 物流基本信息
            // 5. 售后基本信息
            tradeOrderItemDO.setAfterSaleStatus(TradeOrderAfterSaleStatusEnum.NULL.getStatus());
        }
        // 最终保存
        tradeOrderItemMapper.insertList(tradeOrderItemDOs);

        return tradeOrderDO.getId();
    }

    private void createPayTransaction() {

    }

    private String generateTradeOrderNo() {
//    wx
//    2014
//    10
//    27
//    20
//    09
//    39
//    5522657
//    a690389285100
        // 目前的算法
        // 时间序列，年月日时分秒 14 位
        // 纯随机，6 位 TODO 此处估计是会有问题的，后续在调整
        return DateUtil.format(new Date(), "yyyyMMddHHmmss") + // 时间序列
                MathUtil.random(100000, 999999) // 随机。为什么是这个范围，因为偷懒
                ;
    }

}
