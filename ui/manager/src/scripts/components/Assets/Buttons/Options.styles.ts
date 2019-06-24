import { CSSProperties } from 'react';
import { LIGHT_GREY_COLOR2 } from '../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  optionButton: {
    ':hover': {
      backgroundColor: LIGHT_GREY_COLOR2,
    },
    height: '28px',
    width: '28px',
    borderRadius: '10px',
    alignContent: 'center',
  },
  trigger: {
    marginTop: '2px',
    marginLeft: '2px',
    width: '24px',
    height: '24px',
  },
};

export default styles;
