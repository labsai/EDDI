import FormControl from '@material-ui/core/FormControl';
import MenuItem from '@material-ui/core/MenuItem';
import OutlinedInput from '@material-ui/core/OutlinedInput';
import Select from '@material-ui/core/Select';
import { makeStyles } from '@material-ui/core/styles';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import {
  BLUE_COLOR_TRANSPARENT,
  LIGHT_BLUE_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import {
  IDefaultPluginTypes,
  IPluginExtensions,
} from '../../utils/AxiosFunctions';
import Parser from '../../utils/Parser';

const useStyles = makeStyles({
  selectContainer: {
    maxWidth: '40%',
    minWidth: '200px',

    '& .MuiOutlinedInput-root': {
      fontSize: '1.4rem',
      lineHeight: '2rem',
    },
    '& .MuiSelect-iconOutlined': {
      top: 'calc(50% - 8px)',
    },
    '& .MuiOutlinedInput-root.Mui-focused .MuiOutlinedInput-notchedOutline': {
      borderColor: LIGHT_BLUE_COLOR,
    },
  },
  inactive: {
    '& .MuiOutlinedInput-root': {
      color: '#CCC',
    },
  },
  input: {
    '& .MuiOutlinedInput-inputMarginDense': {
      paddingTop: 11,
      paddingBottom: 11,
    },
  },
  select: {
    '& li': {
      fontSize: '1.4rem',
    },
    '& li:hover': {
      backgroundColor: BLUE_COLOR_TRANSPARENT,
    },
    '& .Mui-selected': {
      backgroundColor: LIGHT_BLUE_COLOR,
    },
    '& .Mui-disabled': {
      backgroundColor: 'transparent',
    },
    '& .Mui-selected:hover': {
      backgroundColor: LIGHT_BLUE_COLOR,
    },
  },
});

interface IProps {
  packageExtensions: IDefaultPluginTypes[];
  addExtension: (addedExtension: IPluginExtensions) => void;
}

const VersionDropDownComponent = ({
  packageExtensions,
  addExtension,
}: IProps) => {
  const classes = useStyles();
  const [options, setOptions] = React.useState([]);
  const [option, setOption] = React.useState({
    label: 'Search and add plugins',
    value: 'none',
  });

  React.useEffect(() => {
    const options = packageExtensions.map((option) => ({
      value: option.type,
      label: Parser.getPluginName(option.type, true),
    }));
    setOptions(options);
  }, []);

  React.useEffect(() => {
    const options = packageExtensions.map((option) => ({
      value: option.type,
      label: Parser.getPluginName(option.type, true),
    }));
    setOptions(options);
  }, [packageExtensions]);

  const handleSelect = (
    event: React.ChangeEvent<{
      name?: string;
      value: number;
    }>,
  ) => {
    if (event.target.value) {
      const selectedOption = options.find(
        (o) => o.value === event.target.value,
      );
      if (!selectedOption) {
        return;
      }
      setOption({
        value: selectedOption.value,
        label: selectedOption.label,
      });
      addExtension({ type: `eddi://${selectedOption.value}` });
    }
  };

  return (
    <FormControl
      variant="outlined"
      size="small"
      className={`${classes.selectContainer} ${
        option.value === 'none' ? classes.inactive : undefined
      }`}>
      <Select
        value={option?.value}
        onChange={handleSelect}
        input={<OutlinedInput className={classes.input} />}
        MenuProps={{ classes: { paper: classes.select } }}>
        <MenuItem key={'Plugin select placeholder'} value={'none'} disabled>
          {'Search and add plugins'}
        </MenuItem>
        {options.map((o, i) => {
          return (
            <MenuItem key={o.label + i} value={o.value}>
              {o.label}
            </MenuItem>
          );
        })}
      </Select>
    </FormControl>
  );
};

const ComposedVersionDropDownComponent: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('VersionDropDownComponent'),
)(VersionDropDownComponent);

export default ComposedVersionDropDownComponent;
