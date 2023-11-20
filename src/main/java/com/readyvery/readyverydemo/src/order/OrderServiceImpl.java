package com.readyvery.readyverydemo.src.order;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import net.minidev.json.JSONObject;

import com.readyvery.readyverydemo.domain.Cart;
import com.readyvery.readyverydemo.domain.CartItem;
import com.readyvery.readyverydemo.domain.CartOption;
import com.readyvery.readyverydemo.domain.Foodie;
import com.readyvery.readyverydemo.domain.FoodieOption;
import com.readyvery.readyverydemo.domain.FoodieOptionCategory;
import com.readyvery.readyverydemo.domain.Order;
import com.readyvery.readyverydemo.domain.Progress;
import com.readyvery.readyverydemo.domain.Receipt;
import com.readyvery.readyverydemo.domain.Store;
import com.readyvery.readyverydemo.domain.UserInfo;
import com.readyvery.readyverydemo.domain.repository.CartItemRepository;
import com.readyvery.readyverydemo.domain.repository.CartOptionRepository;
import com.readyvery.readyverydemo.domain.repository.CartRepository;
import com.readyvery.readyverydemo.domain.repository.FoodieOptionRepository;
import com.readyvery.readyverydemo.domain.repository.FoodieRepository;
import com.readyvery.readyverydemo.domain.repository.OrderRepository;
import com.readyvery.readyverydemo.domain.repository.OrdersRepository;
import com.readyvery.readyverydemo.domain.repository.ReceiptRepository;
import com.readyvery.readyverydemo.domain.repository.StoreRepository;
import com.readyvery.readyverydemo.domain.repository.UserRepository;
import com.readyvery.readyverydemo.global.exception.BusinessLogicException;
import com.readyvery.readyverydemo.global.exception.ExceptionCode;
import com.readyvery.readyverydemo.security.jwt.dto.CustomUserDetails;
import com.readyvery.readyverydemo.src.order.config.TossPaymentConfig;
import com.readyvery.readyverydemo.src.order.dto.CartAddReq;
import com.readyvery.readyverydemo.src.order.dto.CartAddRes;
import com.readyvery.readyverydemo.src.order.dto.CartEditReq;
import com.readyvery.readyverydemo.src.order.dto.CartEidtRes;
import com.readyvery.readyverydemo.src.order.dto.CartGetRes;
import com.readyvery.readyverydemo.src.order.dto.CartItemDeleteReq;
import com.readyvery.readyverydemo.src.order.dto.CartItemDeleteRes;
import com.readyvery.readyverydemo.src.order.dto.CartResetRes;
import com.readyvery.readyverydemo.src.order.dto.FoodyDetailRes;
import com.readyvery.readyverydemo.src.order.dto.OrderMapper;
import com.readyvery.readyverydemo.src.order.dto.PaymentReq;
import com.readyvery.readyverydemo.src.order.dto.TosspaymentDto;
import com.readyvery.readyverydemo.src.order.dto.TosspaymentMakeRes;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
	private final CartRepository cartRepository;
	private final CartItemRepository cartItemRepository;
	private final CartOptionRepository cartOptionRepository;
	private final FoodieRepository foodieRepository;
	private final FoodieOptionRepository foodieOptionRepository;
	private final UserRepository userRepository;
	private final StoreRepository storeRepository;
	private final OrderRepository orderRepository;
	private final OrderMapper orderMapper;
	private final TossPaymentConfig tosspaymentConfig;
	private final OrdersRepository ordersRepository;
	private final ReceiptRepository receiptRepository;

	@Override
	public FoodyDetailRes getFoody(Long storeId, Long foodyId, Long inout) {
		Foodie foodie = foodieRepository.findById(foodyId).orElseThrow(
			() -> new BusinessLogicException(ExceptionCode.FOODY_NOT_FOUND)
		);
		return orderMapper.foodieToFoodyDetailRes(foodie, inout);
	}

	@Override
	public CartAddRes addCart(CustomUserDetails userDetails, CartAddReq cartAddReq) {
		UserInfo user = getUserInfo(userDetails);
		Store store = getStore(cartAddReq.getStoreId());
		Foodie foodie = getFoody(cartAddReq.getFoodieId());

		verifyFoodieInStore(store, foodie);
		verifyCartAddReq(foodie, cartAddReq);

		Cart cart = cartRepository.findByUserInfoAndIsDeletedFalse(user).orElseGet(() -> makeCart(user, store));
		verifyItemsInCart(cart, store);
		CartItem cartItem = makeCartItem(cart, foodie, cartAddReq.getCount());
		List<CartOption> cartOptions = cartAddReq.getOptions().stream()
			.map(option -> makeCartOption(cartItem, option))
			.toList();

		cartRepository.save(cart);
		cartItemRepository.save(cartItem);
		cartOptionRepository.saveAll(cartOptions);

		return orderMapper.cartToCartAddRes(cartItem);
	}

	private void verifyItemsInCart(Cart cart, Store store) {
		if (!cart.getStore().equals(store)) {
			throw new BusinessLogicException(ExceptionCode.ITEM_NOT_SAME_STORE);
		}
	}

	@Override
	public CartEidtRes editCart(CustomUserDetails userDetails, CartEditReq cartEditReq) {
		CartItem cartItem = getCartItem(cartEditReq.getIdx());

		verifyCartItem(cartItem, userDetails);

		editCartItem(cartItem, cartEditReq);
		cartItemRepository.save(cartItem);
		return orderMapper.cartToCartEditRes(cartItem);
	}

	@Override
	public CartItemDeleteRes deleteCart(CustomUserDetails userDetails, CartItemDeleteReq cartItemDeleteReq) {
		CartItem cartItem = getCartItem(cartItemDeleteReq.getIdx());

		verifyCartItem(cartItem, userDetails);

		deleteCartItem(cartItem);
		cartItemRepository.save(cartItem);
		return orderMapper.cartToCartItemDeleteRes(cartItem);
	}

	@Override
	public CartResetRes resetCart(CustomUserDetails userDetails) {
		UserInfo user = getUserInfo(userDetails);
		Cart cart = getCart(user);

		resetCartItem(cart);

		cartRepository.save(cart);
		return orderMapper.cartToCartResetRes(cart);
	}

	@Override
	public CartGetRes getCart(CustomUserDetails userDetails, Long inout) {
		UserInfo user = getUserInfo(userDetails);
		Cart cart = getCart(user);

		return orderMapper.cartToCartGetRes(cart, inout);
	}

	@Override
	public TosspaymentMakeRes requestTossPayment(CustomUserDetails userDetails, PaymentReq paymentReq) {
		UserInfo user = getUserInfo(userDetails);
		Cart cart = getCart(user);
		Store store = cart.getStore();

		// Long amount = calculateAmount(store, paymentReq.getCarts(), paymentReq.getInout());
		Long amount = calculateAmount2(cart, paymentReq.getInout());
		//TODO: 쿠폰 추가
		Order order = makeOrder(user, store, amount, cart);
		cartOrder(cart);
		orderRepository.save(order);
		cartRepository.save(cart);
		return orderMapper.orderToTosspaymentMakeRes(order);
	}

	private void cartOrder(Cart cart) {
		cart.setIsOrdered(true);
	}

	@Override
	public String tossPaymentSuccess(String paymentKey, String orderId, Long amount) {
		Order order = getOrder(orderId);
		verifyOrder(order, amount);
		TosspaymentDto tosspaymentDto = requestTossPaymentAccept(paymentKey, orderId, amount);
		applyTosspaymentDto(order, tosspaymentDto);
		orderRepository.save(order);
		//TODO: 영수증 처리
		Receipt receipt = orderMapper.tosspaymentDtoToReceipt(tosspaymentDto, order);
		receiptRepository.save(receipt);
		return "hi";
	}

	private void applyTosspaymentDto(Order order, TosspaymentDto tosspaymentDto) {
		//TODO: orderNumber 적용
		order.setPaymentKey(tosspaymentDto.getPaymentKey());
		order.setMethod(tosspaymentDto.getMethod());
		order.setProgress(Progress.ORDER);
	}

	private void verifyOrder(Order order, Long amount) {
		if (!order.getTotalAmount().equals(amount)) {
			throw new BusinessLogicException(ExceptionCode.TOSS_PAYMENT_AMOUNT_NOT_MATCH);
		}
	}

	private Order getOrder(String orderId) {
		return ordersRepository.findByOrderId(orderId).orElseThrow(
			() -> new BusinessLogicException(ExceptionCode.ORDER_NOT_FOUND)
		);
	}

	private TosspaymentDto requestTossPaymentAccept(String paymentKey, String orderId, Long amount) {
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = makeTossHeader();
		JSONObject params = new JSONObject();

		params.put("amount", amount);
		params.put("orderId", orderId);
		params.put("paymentKey", paymentKey);

		try {
			return restTemplate.postForObject(TossPaymentConfig.CONFIRM_URL,
				new HttpEntity<>(params, headers),
				TosspaymentDto.class);
		} catch (Exception e) {
			System.out.println("e.getMessage() = " + e.getMessage());
			throw new BusinessLogicException(ExceptionCode.TOSS_PAYMENT_SUCCESS_FAIL);
		}
	}

	private HttpHeaders makeTossHeader() {
		HttpHeaders headers = new HttpHeaders();
		String encodedAuthKey = new String(
			Base64.getEncoder().encode((tosspaymentConfig.getTossSecretKey() + ":").getBytes(StandardCharsets.UTF_8)));
		headers.setBasicAuth(encodedAuthKey);
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		return headers;
	}

	private Order makeOrder(UserInfo user, Store store, Long amount, Cart cart) {
		if (cart.getCartItems().isEmpty()) {
			throw new BusinessLogicException(ExceptionCode.CART_NOT_FOUND);
		}
		String orderName = cart.getCartItems().get(0).getFoodie().getName();
		if (cart.getCartItems().size() > 1) {
			orderName += " 외 " + (cart.getCartItems().size() - 1) + "개";
		}
		return Order.builder()
			.userInfo(user)
			.store(store)
			.amount(amount)
			.orderId(UUID.randomUUID().toString())
			.paymentKey(null)
			.orderName(orderName)
			.totalAmount(amount)
			.orderNumber(null)
			.progress(Progress.REQUEST)
			.build();
	}

	// private Long calculateAmount(Store store, List<FoodyDto> carts, Long inout) {
	// 	return carts.stream()
	// 		.map(FoodyDto -> {
	// 			Foodie foodie = getFoody(FoodyDto.getIdx());
	// 			verifyFoodieInStore(store, foodie);
	// 			verifyOption(foodie, FoodyDto.getOptions());
	// 			verifyEssentialOption(foodie, FoodyDto.getOptions());
	//
	// 			Long price = orderMapper.determinePrice(foodie, inout);
	// 			Long totalPrice = FoodyDto.getOptions().stream()
	// 				.map(this::getFoodieOption)
	// 				.map(FoodieOption::getPrice)
	// 				.reduce(price, Long::sum);
	//
	// 			return totalPrice * FoodyDto.getCount();
	// 		})
	// 		.reduce(0L, Long::sum);
	// }

	private Long calculateAmount2(Cart cart, Long inout) {
		return cart.getCartItems().stream()
			.map(cartItem -> {
				Long price = orderMapper.determinePrice(cartItem.getFoodie(), inout);
				Long totalPrice = cartItem.getCartOptions().stream()
					.map(CartOption::getFoodieOption)
					.map(FoodieOption::getPrice)
					.reduce(price, Long::sum);

				return totalPrice * cartItem.getCount();
			}).reduce(0L, Long::sum);
	}

	private void resetCartItem(Cart cart) {
		cart.setIsDeleted(true);
	}

	private Cart getCart(UserInfo user) {
		return cartRepository.findByUserInfoAndIsDeletedFalse(user).orElseThrow(
			() -> new BusinessLogicException(ExceptionCode.CART_NOT_FOUND)
		);
	}

	private void deleteCartItem(CartItem cartItem) {
		cartItem.setIsDeleted(true);
	}

	private void editCartItem(CartItem cartItem, CartEditReq cartEditReq) {
		cartItem.setCount(cartEditReq.getCount());
	}

	private void verifyCartItem(CartItem cartItem, CustomUserDetails userDetails) {
		boolean isCartItemOfUser = cartItem.getCart().getUserInfo().getId().equals(userDetails.getId());
		if (!isCartItemOfUser) {
			throw new BusinessLogicException(ExceptionCode.CART_ITEM_NOT_FOUND);
		}
	}

	private CartItem getCartItem(Long idx) {
		return cartItemRepository.findById(idx).orElseThrow(
			() -> new BusinessLogicException(ExceptionCode.CART_ITEM_NOT_FOUND)
		);
	}

	private CartOption makeCartOption(CartItem cartItem, Long option) {
		FoodieOption foodieOption = getFoodieOption(option);
		return CartOption.builder()
			.cartItem(cartItem)
			.foodieOption(foodieOption)
			.build();
	}

	private FoodieOption getFoodieOption(Long option) {
		return foodieOptionRepository.findById(option).orElseThrow(
			() -> new BusinessLogicException(ExceptionCode.OPTION_NOT_FOUND)
		);
	}

	private CartItem makeCartItem(Cart cart, Foodie foodie, Long count) {
		return CartItem.builder()
			.cart(cart)
			.foodie(foodie)
			.count(count)
			.build();
	}

	private Cart makeCart(UserInfo user, Store store) {
		return Cart.builder()
			.userInfo(user)
			.store(store)
			.build();
	}

	private UserInfo getUserInfo(CustomUserDetails userDetails) {
		return userRepository.findById(userDetails.getId()).orElseThrow(
			() -> new BusinessLogicException(ExceptionCode.USER_NOT_FOUND)
		);
	}

	private Store getStore(Long storeId) {
		return storeRepository.findById(storeId).orElseThrow(
			() -> new BusinessLogicException(ExceptionCode.STORE_NOT_FOUND)
		);
	}

	private void verifyCartAddReq(Foodie foodie, CartAddReq cartAddReq) {
		verifyOption(foodie, cartAddReq.getOptions());
		verifyEssentialOption(foodie, cartAddReq.getOptions());
	}

	private Foodie getFoody(Long foodieId) {
		return foodieRepository.findById(foodieId).orElseThrow(
			() -> new BusinessLogicException(ExceptionCode.FOODY_NOT_FOUND)
		);
	}

	private void verifyFoodieInStore(Store store, Foodie foodie) {
		boolean isFoodieInStore = foodie.getFoodieCategory().getStore().equals(store);
		if (!isFoodieInStore) {
			throw new BusinessLogicException(ExceptionCode.FOODY_NOT_IN_STORE);
		}
	}

	private void verifyOption(Foodie foodie, List<Long> opotions) {
		// foodie안에 있는 옵션들을 Set으로 만들기
		Set<Long> optionSet = foodie.getFoodieOptionCategory().stream()
			.flatMap(foodieOptionCategory -> foodieOptionCategory.getFoodieOptions().stream())
			.map(FoodieOption::getId)
			.collect(Collectors.toSet());

		// 옵션들이 올바른지 확인
		opotions.stream()
			.filter(option -> !optionSet.contains(option))
			.findAny()
			.ifPresent(option -> {
				throw new BusinessLogicException(ExceptionCode.INVALID_OPTION);
			});
	}

	private void verifyEssentialOption(Foodie foodie, List<Long> options) {
		foodie.getFoodieOptionCategory().stream()
			.filter(FoodieOptionCategory::isRequired)
			.collect(Collectors.toSet()).stream()
			.filter(foodieOptionCategory -> foodieOptionCategory.getFoodieOptions().stream()
				.map(FoodieOption::getId)
				.filter(options::contains)
				.count() != 1)  // 필수 값이 1개가 아닌 경우
			.findAny()
			.ifPresent(foodieOptionCategory -> {
				throw new BusinessLogicException(ExceptionCode.INVALID_OPTION_COUNT);
			});
	}
}
