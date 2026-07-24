package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.data.entity.Derivation;
import com.eaiselp.data.mapper.DerivationMapper;
import com.eaiselp.data.service.DerivationService;
import org.springframework.stereotype.Service;

@Service
public class DerivationServiceImpl extends ServiceImpl<DerivationMapper, Derivation> implements DerivationService {
}
