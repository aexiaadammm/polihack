import { ComponentFixture, TestBed } from '@angular/core/testing';

import { OrderingQuestion } from './ordering-question';

describe('OrderingQuestion', () => {
  let component: OrderingQuestion;
  let fixture: ComponentFixture<OrderingQuestion>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [OrderingQuestion],
    }).compileComponents();

    fixture = TestBed.createComponent(OrderingQuestion);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
