package com.eaiselp.data.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eaiselp.data.entity.Case;
import com.eaiselp.data.mapper.CaseMapper;
import com.eaiselp.data.service.CaseService;
import org.springframework.stereotype.Service;

@Service
public class CaseServiceImpl extends ServiceImpl<CaseMapper, Case> implements CaseService {
}
